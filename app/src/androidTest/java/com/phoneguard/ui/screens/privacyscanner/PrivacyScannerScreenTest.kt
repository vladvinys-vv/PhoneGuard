package com.phoneguard.ui.screens.privacyscanner

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
 * Экран сканера приватности: заголовок и фильтр «Все».
 *
 * Раньше проверялись русские строки напрямую — тест не проходил на других локалях.
 */
class PrivacyScannerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun privacyScannerScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                PrivacyScannerScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.privacy_scanner))
            .assertIsDisplayed()
    }

    @Test
    fun privacyScannerScreen_displaysFilterAll() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                PrivacyScannerScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.filter_all))
            .assertIsDisplayed()
    }
}

@Composable
private fun PrivacyScannerScreenPreview() {
    PrivacyScannerScreen()
}
