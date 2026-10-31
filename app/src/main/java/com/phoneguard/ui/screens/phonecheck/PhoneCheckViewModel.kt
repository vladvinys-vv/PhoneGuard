package com.phoneguard.ui.screens.phonecheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.deviceaudit.DeviceAuditChecker
import com.phoneguard.deviceaudit.DeviceAuditReport
import com.phoneguard.util.AnalyticsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel «чека всего телефона».
 *
 * Аудит запускается один раз при открытии экрана (`runAuditIfNeeded`), чтобы список
 * проверок не был пустым даже если отчёт не сохранён целиком (в настройки пишутся
 * только оценка, число проблем и время). Кнопка перезапускает проверку.
 *
 * Раньше документация утверждала обратное («только по нажатию кнопки»), хотя `init`
 * уже запускал аудит — при чтении кода это выглядело как лишняя фоновая работа.
 */
@HiltViewModel
class PhoneCheckViewModel @Inject constructor(
    private val checker: DeviceAuditChecker,
    private val preferencesManager: PreferencesManager,
    private val analyticsHelper: AnalyticsHelper
) : ViewModel() {

    private val _report = MutableStateFlow<DeviceAuditReport?>(null)
    val report: StateFlow<DeviceAuditReport?> = _report.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()

    /** Результат прошлой проверки (для строки «последняя проверка …»). */
    val savedScore: StateFlow<Int?> = preferencesManager.deviceAuditScore
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val savedTime: StateFlow<Long?> = preferencesManager.deviceAuditTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    init {
        // Показываем прошлый результат сразу, чтобы экран не был пустым.
        runAuditIfNeeded()
    }

    private fun runAuditIfNeeded() {
        if (_report.value != null || _isRunning.value) return
        viewModelScope.launch {
            val report = runCatching { checker.performAudit() }.getOrNull() ?: return@launch
            _report.value = report
            // Первая проверка при открытии экрана тоже сохраняется, иначе карточка
            // на дашборде оставалась пустой, хотя аудит уже выполнен.
            persist(report)
        }
    }

    private suspend fun persist(report: DeviceAuditReport) {
        runCatching { preferencesManager.saveDeviceAudit(report.score, report.issueCount, report.timestamp) }
    }

    fun runAudit() {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            _error.value = false
            try {
                val report = checker.performAudit()
                _report.value = report
                persist(report)
                analyticsHelper.logEvent(
                    "device_audit_completed",
                    mapOf(
                        "score" to report.score,
                        "issues" to report.issueCount
                    )
                )
            } catch (_: Exception) {
                _error.value = true
            } finally {
                _isRunning.value = false
            }
        }
    }
}
