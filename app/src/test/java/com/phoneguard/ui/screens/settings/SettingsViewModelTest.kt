package com.phoneguard.ui.screens.settings

import app.cash.turbine.test
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.data.local.ScanHistory
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.data.settings.SettingsRepository
import com.phoneguard.model.FirewallLog
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: SettingsViewModel
    private val preferencesManager: PreferencesManager = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val appDatabase: AppDatabase = mockk()
    private val firewallRepository: FirewallRepository = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { preferencesManager.isDarkTheme } returns MutableStateFlow(false)
        coEvery { preferencesManager.setDarkTheme(any()) } returns Unit
        coEvery { preferencesManager.setLanguage(any()) } returns Unit
        coEvery { settingsRepository.exportLogs(any(), any(), any()) } returns null
        every { appDatabase.blockedLogDao() } returns mockk {
            every { getAllBlockedLogs() } returns flowOf(emptyList())
        }
        every { appDatabase.scanHistoryDao() } returns mockk {
            every { getAllScans() } returns flowOf(emptyList())
        }
        every { firewallRepository.allLogs } returns flowOf(emptyList())
        viewModel = SettingsViewModel(preferencesManager, settingsRepository, appDatabase, firewallRepository)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial isDarkTheme is false`() = runTest {
        assertEquals(false, viewModel.isDarkTheme.value)
    }

    @Test
    fun `initial exportResult is null`() = runTest {
        assertNull(viewModel.exportResult.value)
    }

    @Test
    fun `exportLogs calls repository with real data`() = runTest {
        val blockedLogs = listOf(BlockedLog(phoneNumber = "123", isSms = false))
        val firewallLogs = listOf(FirewallLog(packageName = "com.test", appName = "Test", timestamp = 0, connectionType = "WIFI"))
        val scanHistory = listOf(ScanHistory(timestamp = 0, riskScore = 50, issuesFound = 1, reportJson = "{}"))

        every { appDatabase.blockedLogDao().getAllBlockedLogs() } returns flowOf(blockedLogs)
        every { appDatabase.scanHistoryDao().getAllScans() } returns flowOf(scanHistory)
        every { firewallRepository.allLogs } returns flowOf(firewallLogs)
        coEvery { settingsRepository.exportLogs(blockedLogs, firewallLogs, scanHistory) } returns null

        viewModel.exportLogs()
        coVerify { settingsRepository.exportLogs(blockedLogs, firewallLogs, scanHistory) }
    }
}