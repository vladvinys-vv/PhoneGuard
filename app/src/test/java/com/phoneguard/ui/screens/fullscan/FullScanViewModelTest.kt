package com.phoneguard.ui.screens.fullscan

import com.phoneguard.fullscan.FullScanOrchestrator
import com.phoneguard.fullscan.ScanStage
import com.phoneguard.model.FullScanReport
import com.phoneguard.model.ScanHistory
import com.phoneguard.util.AnalyticsHelper
import com.phoneguard.util.BatteryOptimizationHelper
import com.phoneguard.util.PerformanceMonitor
import com.phoneguard.util.PdfExportHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Полное сканирование: состояния, троттлинг, ошибки, история.
 *
 * Тесты переписаны под текущее поведение `FullScanViewModel`: «сканирование уже
 * идёт» определяется флагом [FullScanViewModel.isScanning] (а не тем, что
 * `progress`/`report` ещё `null`), поэтому старый вариант «два `startScan()`
 * подряд» на мгновенно завершающемся потоке запускал второе сканирование и
 * падал. Здесь поток удерживается [CompletableDeferred].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullScanViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: FullScanViewModel
    private val orchestrator: FullScanOrchestrator = mockk()
    private val performanceMonitor: PerformanceMonitor = mockk()
    private val pdfExportHelper: PdfExportHelper = mockk()
    private val batteryOptimizationHelper: BatteryOptimizationHelper = mockk()
    private val analyticsHelper: AnalyticsHelper = mockk(relaxed = true)
    private val trace: PerformanceMonitor.Trace = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { orchestrator.getAllScans() } returns flowOf(emptyList())
        coEvery { orchestrator.getLatestScanSummary() } returns (null to null)
        every { batteryOptimizationHelper.getAdaptiveScanInterval() } returns TimeUnit.HOURS.toMillis(6)
        every { performanceMonitor.startTrace(any()) } returns trace
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = FullScanViewModel(
        orchestrator,
        performanceMonitor,
        pdfExportHelper,
        batteryOptimizationHelper,
        analyticsHelper
    )

    private fun progress(step: Int = 1) =
        FullScanOrchestrator.FullScanState.Progress(step, 2, ScanStage.ROOT)

    @Test
    fun `startScan toggles isScanning and publishes report`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val report = FullScanReport(riskScore = 10)
        coEvery { orchestrator.runFullScan() } returns flow {
            emit(progress())
            gate.await()
            emit(FullScanOrchestrator.FullScanState.Result(report, 1L))
        }

        viewModel.startScan()

        assertTrue("пока поток не завершён — сканирование идёт", viewModel.isScanning.value)
        assertNotNull("прогресс должен обновляться", viewModel.progress.value)

        gate.complete(Unit)

        assertFalse("после результата флаг снимается", viewModel.isScanning.value)
        assertNull(viewModel.progress.value)
        assertEquals(report, viewModel.report.value)
    }

    @Test
    fun `startScan does not start when already scanning`() = runTest {
        var scanCalls = 0
        val gate = CompletableDeferred<Unit>()
        coEvery { orchestrator.runFullScan() } coAnswers {
            scanCalls++
            flow {
                emit(progress())
                gate.await()
                emit(FullScanOrchestrator.FullScanState.Result(FullScanReport(riskScore = 10), 1L))
            }
        }

        viewModel.startScan()
        viewModel.startScan() // Should be ignored

        assertEquals(1, scanCalls)
        assertEquals(
            FullScanViewModel.ScanBlockedReason.ALREADY_RUNNING,
            viewModel.scanBlockedReason.value
        )

        gate.complete(Unit)
    }

    @Test
    fun `startScan reports scanFailed and resets isScanning when orchestrator throws`() = runTest {
        coEvery { orchestrator.runFullScan() } returns flow {
            emit(progress())
            throw IllegalStateException("boom")
        }

        viewModel.startScan()

        assertFalse("флаг не должен залипнуть в true", viewModel.isScanning.value)
        assertTrue("ошибка видна пользователю", viewModel.scanFailed.value)
        assertNull("ложная блокировка не выставляется", viewModel.scanBlockedReason.value)

        viewModel.clearScanFailed()
        assertFalse(viewModel.scanFailed.value)
    }

    @Test
    fun `loadHistory populates scanHistory`() = runTest {
        val history = listOf(ScanHistory(timestamp = 1000, riskScore = 50, issuesFound = 2))
        coEvery { orchestrator.getAllScans() } returns flowOf(history)

        viewModel.loadHistory()

        assertEquals(history, viewModel.scanHistory.value)
    }

    @Test
    fun `second scan right after the first is throttled`() = runTest {
        var scanCalls = 0
        coEvery { orchestrator.runFullScan() } coAnswers {
            scanCalls++
            flowOf(FullScanOrchestrator.FullScanState.Result(FullScanReport(riskScore = 10), 1L))
        }

        viewModel.startScan() // первый скан завершается сразу и запоминает время
        viewModel.startScan() // интервал 6 ч ещё не прошёл

        assertEquals(1, scanCalls)
        assertEquals(FullScanViewModel.ScanBlockedReason.TOO_SOON, viewModel.scanBlockedReason.value)

        viewModel.clearBlockedReason()
        assertNull(viewModel.scanBlockedReason.value)
    }

    @Test
    fun `exportPdf updates exportPdfResult on success`() = runTest {
        val file = java.io.File.createTempFile("scan_report", ".pdf")
        coEvery { pdfExportHelper.exportScanReport(any()) } returns file

        viewModel.exportPdf(ScanHistory(timestamp = 1000, riskScore = 50, issuesFound = 1))

        assertEquals(file.absolutePath, viewModel.exportPdfResult.value)
        assertFalse(viewModel.exportPdfFailed.value)
        file.delete()
    }

    @Test
    fun `exportPdf reports failure instead of silently doing nothing`() = runTest {
        coEvery { pdfExportHelper.exportScanReport(any()) } returns null

        viewModel.exportPdf(ScanHistory(timestamp = 1000, riskScore = 50, issuesFound = 1))

        assertNull(viewModel.exportPdfResult.value)
        assertTrue("пользователь должен узнать об ошибке", viewModel.exportPdfFailed.value)
    }

    @Test
    fun `clearExportResult clears result and error flag`() = runTest {
        coEvery { pdfExportHelper.exportScanReport(any()) } returns java.io.File.createTempFile("scan", ".pdf")

        viewModel.exportPdf(ScanHistory(timestamp = 1000, riskScore = 50, issuesFound = 1))
        viewModel.clearExportResult()

        assertNull(viewModel.exportPdfResult.value)
        assertFalse(viewModel.exportPdfFailed.value)
    }

    @Test
    fun `getLatestSummary returns orchestrator result`() = runTest {
        coEvery { orchestrator.getLatestScanSummary() } returns (75 to "19.07.2026 10:00")

        val summary = viewModel.getLatestSummary()

        assertEquals(75, summary.first)
        assertEquals("19.07.2026 10:00", summary.second)
    }
}
