package com.phoneguard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.data.local.ScanHistory
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.data.settings.SettingsRepository
import com.phoneguard.model.FirewallLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val settingsRepository: SettingsRepository,
    private val appDatabase: AppDatabase,
    private val firewallRepository: FirewallRepository
) : ViewModel() {

    val isDarkTheme: StateFlow<Boolean> = preferencesManager.isDarkTheme

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
}
