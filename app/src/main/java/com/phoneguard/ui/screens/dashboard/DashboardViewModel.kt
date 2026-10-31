package com.phoneguard.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.fullscan.FullScanOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecurityScore(
    val total: Int = 0,
    val antiTheft: Int = 0,
    val callBlocker: Int = 0,
    val privacy: Int = 0,
    val spyware: Int = 0
)

data class FullScanSummary(
    val riskScore: Int?,
    val dateText: String?
)

/** Итог «чека всего телефона» для карточки на дашборде. */
data class DeviceAuditSummary(
    val score: Int?,
    val issues: Int
)

/**
 * Оценка защищённости для дашборда.
 *
 * Раньше `callBlocker`, `privacy` и `spyware` никогда не вычислялись (всегда
 * `0`), поэтому итоговая оценка не могла превысить 35/100, а `total` считался
 * как простая сумма четырёх подоценок без нормализации. Теперь каждая
 * подоценка считается из реальных настроек, а итог — взвешенное среднее.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val fullScanOrchestrator: FullScanOrchestrator
) : ViewModel() {

    private val _fullScanSummary = MutableStateFlow(FullScanSummary(null, null))
    val fullScanSummary: StateFlow<FullScanSummary> = _fullScanSummary.asStateFlow()

    /**
     * Итог последнего «чека всего телефона» (оценка + число проблем).
     * `null` — проверка ещё не выполнялась.
     */
    val phoneCheckSummary: StateFlow<DeviceAuditSummary> =
        combine(
            preferencesManager.deviceAuditScore,
            preferencesManager.deviceAuditIssues
        ) { score, issues ->
            if (score == null) DeviceAuditSummary(null, 0) else DeviceAuditSummary(score, issues ?: 0)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DeviceAuditSummary(null, 0)
        )

    /** Риск последнего полного сканирования (0..100, чем выше — тем лучше). */
    private val _scanRiskScore = MutableStateFlow<Int?>(null)

    /**
     * Сигналы в фиксированном порядке — индексы используются ниже.
     * Все они `Flow<Boolean>`, поэтому подходят под vararg-версию `combine`.
     */
    private val signals: Array<kotlinx.coroutines.flow.Flow<Boolean>> = arrayOf(
        preferencesManager.isDeviceAdminEnabled,          // 0
        preferencesManager.isSimLockEnabled,              // 1
        preferencesManager.isPhotoOnFailedAttemptsEnabled, // 2
        preferencesManager.blockUnknownNumbers,           // 3
        preferencesManager.blockHiddenNumbers,            // 4
        preferencesManager.blockInternationalNumbers,     // 5
        preferencesManager.isAutoIntrusionResponseEnabled, // 6
        preferencesManager.isIntrusionAlarmEnabled,        // 7
        preferencesManager.isIntrusionPhotoEnabled,        // 8
        preferencesManager.isIntrusionSmsEnabled,          // 9
        preferencesManager.isIntrusionBlockNetworkEnabled, // 10
        preferencesManager.isIntrusionHoneypotEnabled      // 11
    )

    private val partialScore = combine(*signals) { values: Array<Boolean> ->
        SecurityScore(
            antiTheft = calculateAntiTheftScore(values[0], values[1], values[2]),
            callBlocker = calculateCallBlockerScore(values[3], values[4], values[5]),
            privacy = calculatePrivacyScore(
                autoResponse = values[6],
                alarm = values[7],
                photo = values[8],
                sms = values[9],
                blockNetwork = values[10],
                honeypot = values[11]
            )
        )
    }

    val securityScore: StateFlow<SecurityScore> =
        combine(partialScore, _scanRiskScore) { partial, risk ->
            val spyware = risk ?: 0
            partial.copy(
                spyware = spyware,
                total = (partial.antiTheft * 30 + partial.callBlocker * 25 +
                    partial.privacy * 25 + spyware * 20) / 100
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SecurityScore()
        )

    init {
        loadFullScanSummary()
    }

    private fun calculateAntiTheftScore(
        isDeviceAdminEnabled: Boolean,
        isSimLockEnabled: Boolean,
        isPhotoEnabled: Boolean
    ): Int {
        var score = 0
        if (isDeviceAdminEnabled) score += 35
        if (isSimLockEnabled) score += 35
        if (isPhotoEnabled) score += 30
        return score.coerceAtMost(100)
    }

    private fun calculateCallBlockerScore(
        blockUnknown: Boolean,
        blockHidden: Boolean,
        blockInternational: Boolean
    ): Int {
        // Базовая защита от спама включена всегда (локальная база), остальное — опции.
        var score = 40
        if (blockUnknown) score += 20
        if (blockHidden) score += 20
        if (blockInternational) score += 20
        return score.coerceAtMost(100)
    }

    private fun calculatePrivacyScore(
        autoResponse: Boolean,
        alarm: Boolean,
        photo: Boolean,
        sms: Boolean,
        blockNetwork: Boolean,
        honeypot: Boolean
    ): Int {
        val enabled = listOf(autoResponse, alarm, photo, sms, blockNetwork, honeypot).count { it }
        return (enabled * 100 / 6)
    }

    /** Перезагружает сводку сканирования (кнопка «обновить» на дашборде). */
    fun recalculateScore() {
        loadFullScanSummary()
    }

    private fun loadFullScanSummary() {
        viewModelScope.launch {
            val summary = fullScanOrchestrator.getLatestScanSummary()
            _fullScanSummary.value = FullScanSummary(summary.first, summary.second)
            _scanRiskScore.value = summary.first
        }
    }
}
