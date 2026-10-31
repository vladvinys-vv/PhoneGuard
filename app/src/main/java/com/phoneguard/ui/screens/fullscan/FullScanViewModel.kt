package com.phoneguard.ui.screens.fullscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.fullscan.FullScanOrchestrator
import com.phoneguard.model.FullScanReport
import com.phoneguard.model.ScanHistory
import com.phoneguard.util.AnalyticsHelper
import com.phoneguard.util.BatteryOptimizationHelper
import com.phoneguard.util.PdfExportHelper
import com.phoneguard.util.PerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class FullScanViewModel @Inject constructor(
    private val orchestrator: FullScanOrchestrator,
    private val performanceMonitor: PerformanceMonitor,
    private val pdfExportHelper: PdfExportHelper,
    private val batteryOptimizationHelper: BatteryOptimizationHelper,
    private val analyticsHelper: AnalyticsHelper
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

    private val _comparison = MutableStateFlow<ScanComparison?>(null)
    val comparison: StateFlow<ScanComparison?> = _comparison.asStateFlow()

    private val _exportPdfResult = MutableStateFlow<String?>(null)
    val exportPdfResult: StateFlow<String?> = _exportPdfResult.asStateFlow()

    /**
     * Экспорт PDF завершился ошибкой.
     *
     * Раньше и «файл готов», и «не удалось» обозначались `null`, поэтому нажатие
     * «Экспорт PDF» при ошибке просто ничего не делало — без сообщения пользователю.
     */
    private val _exportPdfFailed = MutableStateFlow(false)
    val exportPdfFailed: StateFlow<Boolean> = _exportPdfFailed.asStateFlow()

    /** Ошибка во время сканирования (показать пользователю, а не глотать). */
    private val _scanFailed = MutableStateFlow(false)
    val scanFailed: StateFlow<Boolean> = _scanFailed.asStateFlow()

    fun clearScanFailed() {
        _scanFailed.value = false
    }

    private var historyJob: Job? = null

    init {
        loadHistory()
    }

    /**
     * Запускает полное сканирование.
     *
     * Раньше первый тап мог ничего не делать: троттлинг сравнивал текущее время
     * с `0`, но интервал считался и для «холодного» состояния, а ошибки в
     * расчёте интервала (BatteryManager недоступен) возвращали `0`… Теперь
     * троттлинг применяется только если предыдущее сканирование действительно
     * было, а причина отказа видна в [scanBlockedReason].
     */
    fun startScan() {
        if (_isScanning.value) {
            _scanBlockedReason.value = ScanBlockedReason.ALREADY_RUNNING
            return
        }
        val now = System.currentTimeMillis()
        val lastScan = _lastScanTimestamp.value
        if (lastScan > 0) {
            val minInterval = runCatching { batteryOptimizationHelper.getAdaptiveScanInterval() }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
            if (minInterval > 0 && now - lastScan < minInterval) {
                _scanBlockedReason.value = ScanBlockedReason.TOO_SOON
                return
            }
        }

        _isScanning.value = true
        _report.value = null
        _progress.value = null
        _scanBlockedReason.value = null
        _lastScanTimestamp.value = now
        _comparison.value = null

        // Предыдущий результат берём до старта скана: после вставки нового отчёта
        // история уже содержит текущий скан, и разница всегда была бы нулевой.
        val previousEntry = _scanHistory.value.firstOrNull()

        viewModelScope.launch {
            val trace: PerformanceMonitor.Trace = performanceMonitor.startTrace(PerformanceMonitor.TRACE_SCAN)
            trace.start()
            var gotResult = false
            var failed = false
            try {
                orchestrator.runFullScan().collect { state ->
                    when (state) {
                        is FullScanOrchestrator.FullScanState.Progress -> _progress.value = state
                        is FullScanOrchestrator.FullScanState.Result -> {
                            gotResult = true
                            _report.value = state.report
                            _isScanning.value = false
                            _progress.value = null
                            analyticsHelper.logScanCompleted(
                                riskScore = state.report.riskScore,
                                issuesFound = state.report.issues.size
                            )
                            compareWithPrevious(state.report, previousEntry)
                            loadHistory()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Отмена (уход с экрана) — не ошибка сканирования.
                throw cancelled
            } catch (e: Exception) {
                failed = true
                _progress.value = null
                _scanFailed.value = true
            } finally {
                // Флаг снимается всегда. Если оркестратор сообщил «сканирование уже
                // идёт» (например, его запустил периодический воркер), поток
                // завершается без `Result`, и раньше `isScanning` оставался `true`
                // навсегда: кнопка «Сканировать» больше не работала.
                _isScanning.value = false
                if (!gotResult && !failed) {
                    _scanBlockedReason.value = ScanBlockedReason.ALREADY_RUNNING
                }
                trace.stop()
            }
        }
    }

    private fun compareWithPrevious(currentReport: FullScanReport, previousEntry: ScanHistory?) {
        val previous = previousEntry ?: return
        _comparison.value = ScanComparison(
            riskScoreDiff = currentReport.riskScore - previous.riskScore,
            issuesDiff = currentReport.issues.size - previous.issuesFound
        )
    }

    /** Устанавливается, если запуск сканирования отклонён (слишком рано). */
    private val _scanBlockedReason = MutableStateFlow<ScanBlockedReason?>(null)
    val scanBlockedReason: StateFlow<ScanBlockedReason?> = _scanBlockedReason.asStateFlow()

    fun clearBlockedReason() {
        _scanBlockedReason.value = null
    }

    /**
     * Подписка на историю сканирований — одна на ViewModel.
     *
     * Раньше метод запускал новый `collect` при каждом вызове (в `init` и после
     * каждого скана), и старые подписки продолжали работать: лишние запросы к БД
     * и несколько одновременных сборщиков одного и того же Flow.
     */
    fun loadHistory() {
        if (historyJob?.isActive == true) return
        historyJob = viewModelScope.launch {
            orchestrator.getAllScans().collect { _scanHistory.value = it }
        }
    }

    suspend fun getLatestSummary(): Pair<Int?, String?> =
        orchestrator.getLatestScanSummary()

    fun exportPdf(scan: ScanHistory) {
        viewModelScope.launch {
            _exportPdfFailed.value = false
            val file = runCatching { pdfExportHelper.exportScanReport(scan) }.getOrNull()
            if (file != null) {
                _exportPdfResult.value = file.absolutePath
            } else {
                _exportPdfResult.value = null
                _exportPdfFailed.value = true
            }
        }
    }

    fun clearExportResult() {
        _exportPdfResult.value = null
        _exportPdfFailed.value = false
    }

    data class ScanComparison(
        val riskScoreDiff: Int,
        val issuesDiff: Int
    )

    enum class ScanBlockedReason {
        /** Сканирование уже выполняется. */
        ALREADY_RUNNING,

        /** С прошлого сканирования прошло слишком мало времени. */
        TOO_SOON
    }
}
