package com.phoneguard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.data.settings.SettingsRepository
import com.phoneguard.model.FirewallLog
import com.phoneguard.worker.ScheduledFullScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val settingsRepository: SettingsRepository,
    private val appDatabase: AppDatabase,
    private val firewallRepository: FirewallRepository,
    private val workManager: WorkManager
) : ViewModel() {

    val isDarkTheme: StateFlow<Boolean> = preferencesManager.isDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

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

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDarkTheme(enabled)
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            preferencesManager.setLanguage(lang)
        }
    }

    fun exportLogs() = viewModelScope.launch {
        val blockedLogs = appDatabase.blockedLogDao().getAllBlockedLogs().first()
        val firewallLogs = firewallRepository.allLogs.first()
        val scanHistory = appDatabase.scanHistoryDao().getAllScans().first()
        val file = settingsRepository.exportLogs(blockedLogs, firewallLogs, scanHistory)
        _exportResult.value = if (file != null) {
            "Экспорт сохранён: ${file.absolutePath}"
        } else {
            "Ошибка экспорта логов"
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

    fun deleteAllData() = viewModelScope.launch {
        appDatabase.blockedNumberDao().deleteAll()
        appDatabase.blockedLogDao().clearAllLogs()
        appDatabase.firewallDao().clearLogs()
        appDatabase.firewallDao().clearRules()
        appDatabase.scanHistoryDao().deleteAll()
        appDatabase.vaultDao().deleteAll()
        appDatabase.simSwapEventDao().deleteAll()
        preferencesManager.setFirstLaunchDone()
        _exportResult.value = "Все данные удалены"
    }

    private fun schedulePeriodicScan() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresCharging(false)
            .build()

        val request = PeriodicWorkRequestBuilder<ScheduledFullScanWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "scheduled_full_scan",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun cancelPeriodicScan() {
        workManager.cancelUniqueWork("scheduled_full_scan")
    }
}
