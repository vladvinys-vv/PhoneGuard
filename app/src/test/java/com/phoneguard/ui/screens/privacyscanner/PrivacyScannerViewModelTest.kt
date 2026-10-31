package com.phoneguard.ui.screens.privacyscanner

import android.graphics.drawable.Drawable
import com.phoneguard.data.repository.PrivacyScannerRepository
import com.phoneguard.model.AppCategory
import com.phoneguard.model.AppPermission
import com.phoneguard.model.PrivacyApp
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test

/**
 * Сканер приватности: загрузка списка и фильтры.
 *
 * Раньше тесты перезаглушали `repository.getInstalledApps()` уже **после**
 * создания ViewModel: `init` успевал подписаться на пустой поток, поэтому
 * список оставался пустым и проверки падали. Здесь ViewModel создаётся после
 * заглушки. Иконка `PrivacyApp` не nullable (`Drawable`), поэтому вместо `null`
 * используется mockk — иначе файл не компилировался.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyScannerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repository: PrivacyScannerRepository = mockk()
    private val icon: Drawable = mockk(relaxed = true)

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { repository.getInstalledApps() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun newViewModel(): PrivacyScannerViewModel {
        return PrivacyScannerViewModel(repository)
    }

    private fun app(packageName: String, excessive: Boolean): PrivacyApp = PrivacyApp(
        packageName = packageName,
        appName = packageName.uppercase(),
        icon = icon,
        permissions = listOf(AppPermission("android.permission.CAMERA", true, null)),
        hasExcessivePermissions = excessive,
        installTime = 0,
        category = AppCategory.UNKNOWN
    )

    @Test
    fun `apps load from repository`() = runTest {
        val apps = listOf(app("com.example.app", excessive = false))
        coEvery { repository.getInstalledApps() } returns flowOf(apps)

        val viewModel = newViewModel()

        assertEquals(apps, viewModel.apps.value)
    }

    @Test
    fun `filter All returns all apps`() = runTest {
        val apps = listOf(app("com.a", false), app("com.b", true))
        coEvery { repository.getInstalledApps() } returns flowOf(apps)

        val viewModel = newViewModel()

        // `filteredApps` — `stateIn(WhileSubscribed())`, поэтому проверяем через
        // подписку (`first {}`), а не через `.value`: до подписки там initialValue.
        assertEquals(2, viewModel.filteredApps.first { it.size == 2 }.size)
    }

    @Test
    fun `filter Excessive returns only excessive apps`() = runTest {
        val apps = listOf(app("com.a", false), app("com.b", true))
        coEvery { repository.getInstalledApps() } returns flowOf(apps)

        val viewModel = newViewModel()
        viewModel.setFilter(PrivacyFilter.Excessive)

        val filtered = viewModel.filteredApps.first { it.size == 1 }
        assertEquals(listOf("com.b"), filtered.map { it.packageName })
    }
}
