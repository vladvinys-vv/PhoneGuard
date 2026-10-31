package com.phoneguard.ui.screens.callblocker

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.R
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

/**
 * Экран блокировщика: заголовок и вкладки.
 *
 * Тексты берутся из ресурсов, а не хардкодятся по-русски: на устройстве с
 * английской или немецкой локалью прежние проверки падали.
 */
class CallBlockerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun callBlockerScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                CallBlockerScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.call_sms_blocker))
            .assertIsDisplayed()
    }

    @Test
    fun callBlockerScreen_displaysTabs() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                CallBlockerScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.blacklist))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.whitelist))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.blocked_log))
            .assertIsDisplayed()
    }
}

@Composable
private fun CallBlockerScreenPreview() {
    CallBlockerScreen(viewModel = androidx.lifecycle.viewmodel.compose.viewModel())
}
