package com.phoneguard.ui.screens.phonecheck

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.R
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.deviceaudit.DeviceAuditChecker
import com.phoneguard.ui.theme.PhoneGuardTheme
import com.phoneguard.util.AnalyticsHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/**
 * Экран «чек всего телефона»: проверяем, что заголовок и кнопка запуска
 * отображаются. Checker настоящий (audit безопасен и не требует разрешений),
 * настройки и аналитика — заглушки.
 */
class PhoneCheckScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun createViewModel(): PhoneCheckViewModel {
        val preferencesManager = mockk<PreferencesManager>(relaxed = true)
        every { preferencesManager.deviceAuditScore } returns flowOf(null)
        every { preferencesManager.deviceAuditIssues } returns flowOf(null)
        every { preferencesManager.deviceAuditTime } returns flowOf(null)

        return PhoneCheckViewModel(
            checker = DeviceAuditChecker(context),
            preferencesManager = preferencesManager,
            analyticsHelper = mockk<AnalyticsHelper>(relaxed = true)
        )
    }

    @Test
    fun phoneCheckScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                PhoneCheckScreen(viewModel = createViewModel())
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.phone_check))
            .assertIsDisplayed()
    }

    @Test
    fun phoneCheckScreen_displaysRunButton() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                PhoneCheckScreen(viewModel = createViewModel())
            }
        }

        composeTestRule
            .onNodeWithText(
                context.getString(R.string.phone_check_run),
                substring = true
            )
            .assertIsDisplayed()
    }
}
