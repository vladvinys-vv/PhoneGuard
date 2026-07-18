package com.phoneguard.ui.screens.dashboard

import app.cash.turbine.test
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.fullscan.FullScanOrchestrator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: DashboardViewModel
    private val preferencesManager: PreferencesManager = mockk()
    private val fullScanOrchestrator: FullScanOrchestrator = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { preferencesManager.isDeviceAdminEnabled } returns flowOf(true)
        coEvery { preferencesManager.isSimLockEnabled } returns flowOf(false)
        coEvery { preferencesManager.isPhotoOnFailedAttemptsEnabled } returns flowOf(true)
        coEvery { fullScanOrchestrator.getLatestScanSummary() } returns (50 to "18.07.2026 12:00")
        viewModel = DashboardViewModel(preferencesManager, fullScanOrchestrator)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `security score calculates antiTheft correctly`() = runTest {
        viewModel.securityScore.test {
            val score = awaitItem()
            assertEquals(70, score.antiTheft) // 35 + 0 + 35 = 70
            assertEquals(70, score.total)
        }
    }

    @Test
    fun `fullScanSummary loads from orchestrator`() = runTest {
        viewModel.fullScanSummary.test {
            val summary = awaitItem()
            assertEquals(50, summary.riskScore)
            assertEquals("18.07.2026 12:00", summary.dateText)
        }
    }
}
