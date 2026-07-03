package com.phoneguard.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.fullscan.FullScanOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
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

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val fullScanOrchestrator: FullScanOrchestrator
) : ViewModel() {

    private val _securityScore = MutableStateFlow(SecurityScore())
    val securityScore: StateFlow<SecurityScore> = _securityScore.asStateFlow()

    private val _fullScanSummary = MutableStateFlow<FullScanSummary>(FullScanSummary(null, null))
    val fullScanSummary: StateFlow<FullScanSummary> = _fullScanSummary.asStateFlow()

    init {
        calculateSecurityScore()
        loadFullScanSummary()
    }

    private fun calculateSecurityScore() {
        viewModelScope.launch {
            preferencesManager.isDeviceAdminEnabled.collect { isDeviceAdminEnabled ->
                preferencesManager.isSimLockEnabled.collect { isSimLockEnabled ->
                    preferencesManager.isPhotoOnFailedAttemptsEnabled.collect { isPhotoEnabled ->
                        val antiTheftScore = calculateAntiTheftScore(
                            isDeviceAdminEnabled,
                            isSimLockEnabled,
                            isPhotoEnabled
                        )
                        _securityScore.update {
                            it.copy(
                                antiTheft = antiTheftScore,
                                total = antiTheftScore + it.callBlocker + it.privacy + it.spyware
                            )
                        }
                    }
                }
            }
        }
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

    fun recalculateScore() {
        calculateSecurityScore()
    }

    private fun loadFullScanSummary() {
        viewModelScope.launch {
            val summary = fullScanOrchestrator.getLatestScanSummary()
            _fullScanSummary.value = FullScanSummary(summary.first, summary.second)
        }
    }
}
