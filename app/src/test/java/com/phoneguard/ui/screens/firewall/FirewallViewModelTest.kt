package com.phoneguard.ui.screens.firewall

import app.cash.turbine.test
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import io.mockk.coVerify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirewallViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: FirewallViewModel
    private val repository: FirewallRepository = mockk()
    private val context: android.content.Context = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { repository.allRules } returns flowOf(emptyList())
        every { repository.allLogs } returns flowOf(emptyList())
        coEvery { repository.getRuleForPackage(any()) } returns null
        every { context.packageManager } returns mockk(relaxed = true)
        viewModel = FirewallViewModel(repository, context)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial apps list is empty`() = runTest {
        viewModel.apps.test {
            assertEquals(emptyList<AppInfo>(), awaitItem())
        }
    }

    @Test
    fun `initial isVpnActive is false`() = runTest {
        viewModel.isVpnActive.test {
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `upsertRule calls repository`() = runTest {
        val rule = FirewallRule(
            packageName = "com.test",
            appName = "Test",
            blockWifi = true,
            blockMobile = false,
            blockAll = false,
            blockVpn = false,
            blockBackground = false,
            blockedDomains = "[]",
            blockedIps = "[]",
            allowByDefault = true,
            allowWifiOnly = false,
            allowMobileOnly = false,
            updatedAt = System.currentTimeMillis()
        )
        coEvery { repository.upsertRule(rule) } returns Unit
        viewModel.upsertRule(rule)
        coVerify { repository.upsertRule(rule) }
    }

    @Test
    fun `stopVpn sets isVpnActive to false`() = runTest {
        viewModel.isVpnActive.test {
            assertEquals(false, awaitItem())
            viewModel.stopVpn()
            assertEquals(false, awaitItem())
        }
    }
}