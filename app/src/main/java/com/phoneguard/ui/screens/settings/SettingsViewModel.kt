package com.phoneguard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import androidx.work.WorkManager
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.data.settings.SettingsRepository
import com.phoneguard.threatintel.ThreatIntelRepository
import com.phoneguard.worker.ScheduledFullScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Результат экспорта логов — без готового текста, чтобы локализовать в UI. */
sealed class ExportResult {
    data class Success(val path: String) : ExportResult()
    object Failure : ExportResult()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val settingsRepository: SettingsRepository,
    private val appDatabase: AppDatabase,
    private val firewallRepository: FirewallRepository,
    private val workManager: WorkManager,
    private val threatIntelRepository: ThreatIntelRepository
) : ViewModel() {

    val isDarkTheme: StateFlow<Boolean> = preferencesManager.isDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    /** Выбранный язык интерфейса (`ru`/`en`). */
    val language: StateFlow<String> = preferencesManager.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), PreferencesManager.defaultLanguage())

    val isScheduledScanEnabled: StateFlow<Boolean> = preferencesManager.isScheduledScanEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val isAutoIntrusionResponseEnabled: StateFlow<Boolean> = preferencesManager.isAutoIntrusionResponseEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val isIntrusionAlarmEnabled: StateFlow<Boolean> = preferencesManager.isIntrusionAlarmEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val isIntrusionPhotoEnabled: StateFlow<Boolean> = preferencesManager.isIntrusionPhotoEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val isIntrusionSmsEnabled: StateFlow<Boolean> = preferencesManager.isIntrusionSmsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val isIntrusionBlockNetworkEnabled: StateFlow<Boolean> = preferencesManager.isIntrusionBlockNetworkEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val isIntrusionHoneypotEnabled: StateFlow<Boolean> = preferencesManager.isIntrusionHoneypotEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    /** Opt-in сетевая проверка доменов (по умолчанию выключена). */
    val isThreatIntelEnabled: StateFlow<Boolean> = preferencesManager.isThreatIntelEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    /** Сбрасывает результат после показа, иначе Toast повторялся на каждой рекомпозиции. */
    fun consumeExportResult() {
        _exportResult.value = null
    }

    private val _dataClearFailed = MutableStateFlow(false)
    val dataClearFailed: StateFlow<Boolean> = _dataClearFailed.asStateFlow()

    fun consumeDataClearFailed() {
        _dataClearFailed.value = false
    }

    private val _dataCleared = MutableStateFlow(false)
    /** Данные удалены — экран показывает подтверждение и сбрасывает флаг. */
    val dataCleared: StateFlow<Boolean> = _dataCleared.asStateFlow()

    fun consumeDataCleared() {
        _dataCleared.value = false
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDarkTheme(enabled)
        }
    }

    fun setLanguage(lang: String) {
        // Синхронно фиксируем язык для attachBaseContext/recreate(), затем — в DataStore.
        preferencesManager.saveLanguageSync(lang)
        viewModelScope.launch {
            preferencesManager.setLanguage(lang)
        }
    }

    fun exportLogs() = viewModelScope.launch {
        // Раньше исключение из DAO (например, из-за занятой БД) валило корутину и
        // пользователь не видел ни успеха, ни ошибки — кнопка «молчала».
        val result = runCatching {
            val blockedLogs = appDatabase.blockedLogDao().getAllBlockedLogs().first()
            val firewallLogs = firewallRepository.allLogs.first()
            val scanHistory = appDatabase.scanHistoryDao().getAllScans().first()
            settingsRepository.exportLogs(blockedLogs, firewallLogs, scanHistory)
        }.getOrNull()
        _exportResult.value = if (result != null) {
            ExportResult.Success(result.absolutePath)
        } else {
            ExportResult.Failure
        }
    }

    fun setScheduledScan(enabled: Boolean) = viewModelScope.launch {
        preferencesManager.setScheduledScanEnabled(enabled)
        if (enabled) {
            schedulePeriodicScan()
        } else {
            cancelPeriodicScan()
        }
    }

    fun setAutoIntrusionResponse(enabled: Boolean) = viewModelScope.launch { preferencesManager.setAutoIntrusionResponseEnabled(enabled) }
    fun setIntrusionAlarm(enabled: Boolean) = viewModelScope.launch { preferencesManager.setIntrusionAlarmEnabled(enabled) }
    fun setIntrusionPhoto(enabled: Boolean) = viewModelScope.launch { preferencesManager.setIntrusionPhotoEnabled(enabled) }
    fun setIntrusionSms(enabled: Boolean) = viewModelScope.launch { preferencesManager.setIntrusionSmsEnabled(enabled) }
    fun setIntrusionBlockNetwork(enabled: Boolean) = viewModelScope.launch { preferencesManager.setIntrusionBlockNetworkEnabled(enabled) }
    fun setIntrusionHoneypot(enabled: Boolean) = viewModelScope.launch { preferencesManager.setIntrusionHoneypotEnabled(enabled) }

    fun setThreatIntelEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesManager.setThreatIntelEnabled(enabled)
        if (!enabled) {
            // При выключении сбрасываем кэш, чтобы не хранить результаты сетевых проверок.
            threatIntelRepository.clearCache()
        }
    }

    /**
     * Полное удаление пользовательских данных.
     *
     * Что было не так: журнал вторжений (`intrusion_events`), спам-номера и файлы
     * приложения (снимки нарушителя, приманки, отчёты о падениях, экспорт) не
     * удалялись; ошибка любого шага валила корутину, а флаг «данные удалены»
     * выставлялся в любом случае.
     */
    fun deleteAllData() = viewModelScope.launch {
        val outcome = runCatching {
            appDatabase.blockedNumberDao().deleteAll()
            appDatabase.blockedLogDao().clearAllLogs()
            appDatabase.firewallDao().clearLogs()
            appDatabase.firewallDao().clearRules()
            appDatabase.scanHistoryDao().deleteAll()
            appDatabase.vaultDao().deleteAll()
            appDatabase.simSwapEventDao().deleteAll()
            appDatabase.intrusionEventDao().deleteAll()
            appDatabase.spamNumberDao().deleteAll()
            // После полной очистки база спам-номеров засевается заново при старте.
            preferencesManager.resetSpamDatabaseSeeded()
            settingsRepository.deleteLocalFiles()
        }
        outcome.onFailure { Log.w(TAG, "Полная очистка данных завершилась ошибкой", it) }
        _dataClearFailed.value = outcome.isFailure
        _dataCleared.value = outcome.isSuccess
        if (outcome.isSuccess) preferencesManager.setFirstLaunchDone()
    }

    private companion object {
        private const val TAG = "SettingsViewModel"
    }

    private fun schedulePeriodicScan() {
        ScheduledFullScanWorker.schedule(workManager)
    }

    private fun cancelPeriodicScan() {
        ScheduledFullScanWorker.cancel(workManager)
    }
}
