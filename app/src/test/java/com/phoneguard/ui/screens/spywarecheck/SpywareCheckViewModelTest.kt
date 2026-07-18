package com.phoneguard.ui.screens.spywarecheck

import app.cash.turbine.test
import com.phoneguard.data.repository.SpywareCheckRepository
import com.phoneguard.model.SpywareScanResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpywareCheckViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: SpywareCheckViewModel
    private val repository: SpywareCheckRepository = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val result = SpywareScanResult(
            apps = emptyList(),
            totalRiskScore = 0,
            deviceAdminApps = emptyList(),
            accessibilityServices = emptyList(),
            sideloadedApps = emptyList(),
            hiddenApps = emptyList()
        )
        coEvery { repository.scanForSpyware() } returns flowOf(result)
        viewModel = SpywareCheckViewModel(repository)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial scanResult is empty`() = runTest {
        assertEquals(0, viewModel.scanResult.value.totalRiskScore)
        assertEquals(true, viewModel.scanResult.value.apps.isEmpty())
    }

    @Test
    fun `scanForSpyware updates scanResult`() = runTest {
        val result = SpywareScanResult(
            apps = emptyList(),
            totalRiskScore = 42,
            deviceAdminApps = emptyList(),
            accessibilityServices = emptyList(),
            sideloadedApps = emptyList(),
            hiddenApps = emptyList()
        )
        coEvery { repository.scanForSpyware() } returns flowOf(result)

        viewModel.scanForSpyware()
        viewModel.scanResult.test {
            assertEquals(42, awaitItem().totalRiskScore)
        }
    }
}
