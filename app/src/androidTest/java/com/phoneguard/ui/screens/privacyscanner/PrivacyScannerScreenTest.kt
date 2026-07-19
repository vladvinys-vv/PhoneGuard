package com.phoneguard.ui.screens.privacyscanner

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

class PrivacyScannerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun privacyScannerScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                PrivacyScannerScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Сканер конфиденциальности").assertIsDisplayed()
    }

    @Test
    fun privacyScannerScreen_displaysFilterAll() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                PrivacyScannerScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Все").assertIsDisplayed()
    }
}

@Composable
private fun PrivacyScannerScreenPreview() {
    PrivacyScannerScreen()
}
