package com.phoneguard.ui.screens.privacyscanner

import app.cash.turbine.test
import com.phoneguard.data.repository.PrivacyScannerRepository
import com.phoneguard.model.AppPermission
import com.phoneguard.model.AppCategory
import com.phoneguard.model.PrivacyApp
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
class PrivacyScannerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: PrivacyScannerViewModel
    private val repository: PrivacyScannerRepository = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { repository.getInstalledApps() } returns flowOf(emptyList())
        viewModel = PrivacyScannerViewModel(repository)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `apps start empty then load from repository`() = runTest {
        val apps = listOf(
            PrivacyApp(
                packageName = "com.example.app",
                appName = "Example",
                icon = null,
                permissions = listOf(AppPermission("CAMERA", true, null)),
                hasExcessivePermissions = false,
                installTime = 0,
                category = AppCategory.TOOLS
            )
        )
        coEvery { repository.getInstalledApps() } returns flowOf(apps)

        viewModel.apps.test {
            assertEquals(apps, awaitItem())
        }
    }

    @Test
    fun `filter All returns all apps`() = runTest {
        val apps = listOf(
            PrivacyApp("com.a", "A", null, emptyList(), false, 0, AppCategory.UNKNOWN),
            PrivacyApp("com.b", "B", null, emptyList(), true, 0, AppCategory.UNKNOWN)
        )
        coEvery { repository.getInstalledApps() } returns flowOf(apps)

        viewModel.filteredApps.test {
            assertEquals(2, awaitItem().size)
        }
    }

    @Test
    fun `filter Excessive returns only excessive apps`() = runTest {
        val apps = listOf(
            PrivacyApp("com.a", "A", null, emptyList(), false, 0, AppCategory.UNKNOWN),
            PrivacyApp("com.b", "B", null, emptyList(), true, 0, AppCategory.UNKNOWN)
        )
        coEvery { repository.getInstalledApps() } returns flowOf(apps)

        viewModel.setFilter(PrivacyFilter.Excessive)
        viewModel.filteredApps.test {
            assertEquals(1, awaitItem().size)
            assertEquals("com.b", awaitItem().first().packageName)
        }
    }
}
