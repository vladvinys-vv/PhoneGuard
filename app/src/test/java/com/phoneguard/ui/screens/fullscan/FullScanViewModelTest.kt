package com.phoneguard.ui.screens.fullscan

import app.cash.turbine.test
import com.phoneguard.fullscan.FullScanOrchestrator
import com.phoneguard.model.FullScanReport
import com.phoneguard.model.ScanHistory
import com.phoneguard.util.BatteryOptimizationHelper
import com.phoneguard.util.PerformanceMonitor
import com.phoneguard.util.PdfExportHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FullScanViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: FullScanViewModel
    private val orchestrator: FullScanOrchestrator = mockk()
    private val performanceMonitor: PerformanceMonitor = mockk()
    private val pdfExportHelper: PdfExportHelper = mockk()
    private val batteryOptimizationHelper: BatteryOptimizationHelper = mockk()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { orchestrator.getAllScans() } returns flowOf(emptyList())
        coEvery { orchestrator.getLatestScanSummary() } returns (null to null)
        every { batteryOptimizationHelper.getAdaptiveScanInterval() } returns TimeUnit.HOURS.toMillis(6)
        viewModel = FullScanViewModel(orchestrator, performanceMonitor, pdfExportHelper, batteryOptimizationHelper)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startScan sets isScanning true during scan`() = runTest {
        val progressStates = mutableListOf<FullScanOrchestrator.FullScanState.Progress>()
        val resultStates = mutableListOf<FullScanOrchestrator.FullScanState.Result>()

        coEvery {
            orchestrator.runFullScan()
        } returns flowOf(
            FullScanOrchestrator.FullScanState.Progress(1, 2, "Stage 1"),
            FullScanOrchestrator.FullScanState.Result(FullScanReport(riskScore = 10), 1L)
        )

        viewModel.isScanning.test {
            assertFalse(awaitItem())

            viewModel.startScan()

            assertTrue(awaitItem())
            progressStates.clear()
            resultStates.clear()

            // Collect emissions
            viewModel.progress.test {
                progressStates.addAll(awaitItem()?.let { listOf(it) } ?: emptyList())
            }
            viewModel.report.test {
                resultStates.addAll(awaitItem()?.let { listOf(it) } ?: emptyList())
            }

            assertFalse(awaitItem())
        }
    }

    @Test
    fun `startScan does not start when already scanning`() = runTest {
        var scanCalls = 0
        coEvery {
            orchestrator.runFullScan()
        } coAnswers {
            scanCalls++
            flowOf(FullScanOrchestrator.FullScanState.Result(FullScanReport(riskScore = 10), 1L))
        }

        viewModel.startScan()
        viewModel.startScan() // Should be ignored

        assertEquals(1, scanCalls)
    }

    @Test
    fun `loadHistory populates scanHistory`() = runTest {
        val history = listOf(ScanHistory(timestamp = 1000, riskScore = 50, issuesFound = 2))
        coEvery { orchestrator.getAllScans() } returns flowOf(history)

        viewModel.scanHistory.test {
            assertEquals(history, awaitItem())
        }
    }
}
