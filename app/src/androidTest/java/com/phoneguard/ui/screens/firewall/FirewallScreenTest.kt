package com.phoneguard.ui.screens.firewall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.R
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.ui.theme.PhoneGuardTheme
import com.phoneguard.util.InstalledAppsCache
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/**
 * Раньше тест создавал Hilt-ViewModel через `viewModel()` без Hilt-тестового
 * окружения (падение при запуске) и проверял строку «MVP режим», которой в
 * интерфейсе уже нет. Теперь ViewModel собирается вручную с заглушками,
 * а проверяется реальный заголовок экрана.
 */
class FirewallScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun createViewModel(): FirewallViewModel {
        val repository = mockk<FirewallRepository>(relaxed = true)
        every { repository.allRules } returns flowOf(emptyList())
        every { repository.allLogs } returns flowOf(emptyList())
        coEvery { repository.getPagedLogs(any(), any()) } returns emptyList()
        coEvery { repository.getLogsCount() } returns 0
        coEvery { repository.getAllLogs() } returns emptyList()

        return FirewallViewModel(
            repository = repository,
            context = context,
            installedAppsCache = InstalledAppsCache(context)
        )
    }

    @Test
    fun firewallScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                FirewallScreen(viewModel = createViewModel())
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.firewall))
            .assertIsDisplayed()
    }

    @Test
    fun firewallScreen_displaysLimitedModeNotice() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                FirewallScreen(viewModel = createViewModel())
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.firewall_limited_mode_notice))
            .assertIsDisplayed()
    }
}
