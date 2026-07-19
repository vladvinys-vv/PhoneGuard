package com.phoneguard.ui.screens.fullscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.fullscan.FullScanOrchestrator
import com.phoneguard.model.FullScanReport
import com.phoneguard.model.ScanHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class FullScanViewModel @Inject constructor(
    private val orchestrator: FullScanOrchestrator
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _progress = MutableStateFlow<FullScanOrchestrator.FullScanState.Progress?>(null)
    val progress: StateFlow<FullScanOrchestrator.FullScanState.Progress?> = _progress.asStateFlow()

    private val _report = MutableStateFlow<FullScanReport?>(null)
    val report: StateFlow<FullScanReport?> = _report.asStateFlow()

    private val _scanHistory = MutableStateFlow<List<ScanHistory>>(emptyList())
    val scanHistory: StateFlow<List<ScanHistory>> = _scanHistory.asStateFlow()

    private val _lastScanTimestamp = MutableStateFlow<Long>(0)
    val lastScanTimestamp: StateFlow<Long> = _lastScanTimestamp.asStateFlow()

    init {
        loadHistory()
    }

    fun startScan() {
        if (_isScanning.value) return
        val now = System.currentTimeMillis()
        if (now - _lastScanTimestamp.value < TimeUnit.HOURS.toMillis(6)) {
            return
        }
        _isScanning.value = true
        _report.value = null
        _lastScanTimestamp.value = now

        viewModelScope.launch {
            orchestrator.runFullScan().collect { state ->
                when (state) {
                    is FullScanOrchestrator.FullScanState.Progress -> _progress.value = state
                    is FullScanOrchestrator.FullScanState.Result -> {
                        _report.value = state.report
                        _isScanning.value = false
                        loadHistory()
                    }
                }
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            orchestrator.getAllScans().collect { _scanHistory.value = it }
        }
    }

    suspend fun getLatestSummary(): Pair<Int?, String?> =
        orchestrator.getLatestScanSummary()
}
