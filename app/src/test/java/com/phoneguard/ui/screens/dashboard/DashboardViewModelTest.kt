package com.phoneguard.ui.screens.dashboard

import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.fullscan.FullScanOrchestrator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        // Anti-theft
        every { preferencesManager.isDeviceAdminEnabled } returns flowOf(true)
        every { preferencesManager.isSimLockEnabled } returns flowOf(false)
        every { preferencesManager.isPhotoOnFailedAttemptsEnabled } returns flowOf(true)
        // Call blocker
        every { preferencesManager.blockUnknownNumbers } returns flowOf(true)
        every { preferencesManager.blockHiddenNumbers } returns flowOf(false)
        every { preferencesManager.blockInternationalNumbers } returns flowOf(false)
        // Privacy / intrusion
        every { preferencesManager.isAutoIntrusionResponseEnabled } returns flowOf(true)
        every { preferencesManager.isIntrusionAlarmEnabled } returns flowOf(true)
        every { preferencesManager.isIntrusionPhotoEnabled } returns flowOf(true)
        every { preferencesManager.isIntrusionSmsEnabled } returns flowOf(true)
        every { preferencesManager.isIntrusionBlockNetworkEnabled } returns flowOf(true)
        every { preferencesManager.isIntrusionHoneypotEnabled } returns flowOf(false)
        // Чек всего телефона
        every { preferencesManager.deviceAuditScore } returns flowOf(92)
        every { preferencesManager.deviceAuditIssues } returns flowOf(1)

        coEvery { fullScanOrchestrator.getLatestScanSummary() } returns (50 to "18.07.2026 12:00")
        viewModel = DashboardViewModel(preferencesManager, fullScanOrchestrator)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `anti theft score weights device admin, sim lock and photo`() = runTest {
        val score = viewModel.securityScore.first { it.antiTheft > 0 }
        // 35 (device admin) + 0 (sim lock выключен) + 30 (фото) = 65
        assertEquals(65, score.antiTheft)
    }

    @Test
    fun `call blocker score reflects blocking settings`() = runTest {
        val score = viewModel.securityScore.first { it.antiTheft > 0 }
        // База 40 + 20 за «блокировать неизвестные» = 60
        assertEquals(60, score.callBlocker)
    }

    @Test
    fun `privacy score counts enabled intrusion features`() = runTest {
        val score = viewModel.securityScore.first { it.antiTheft > 0 }
        // 5 из 6 включены -> 83
        assertEquals(83, score.privacy)
    }

    @Test
    fun `spyware score comes from the latest full scan`() = runTest {
        val score = viewModel.securityScore.first { it.antiTheft > 0 }
        assertEquals(50, score.spyware)
    }

    @Test
    fun `total score is a weighted average and stays within 0-100`() = runTest {
        val score = viewModel.securityScore.first { it.antiTheft > 0 }
        val expected = (65 * 30 + 60 * 25 + 83 * 25 + 50 * 20) / 100
        assertEquals(expected, score.total)
        assertTrue(score.total in 0..100)
    }

    @Test
    fun `fullScanSummary loads from orchestrator`() = runTest {
        val summary = viewModel.fullScanSummary.first()
        assertEquals(50, summary.riskScore)
        assertEquals("18.07.2026 12:00", summary.dateText)
    }

    @Test
    fun `phone check summary exposes last audit score and issues`() = runTest {
        val summary = viewModel.phoneCheckSummary.first { it.score != null }
        assertEquals(92, summary.score)
        assertEquals(1, summary.issues)
    }
}
