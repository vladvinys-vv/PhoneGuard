package com.phoneguard.ui.screens.callblocker

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

class CallBlockerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun callBlockerScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                CallBlockerScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Блокировщик звонков & SMS").assertIsDisplayed()
    }

    @Test
    fun callBlockerScreen_displaysTabs() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                CallBlockerScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Чёрный список").assertIsDisplayed()
        composeTestRule.onNodeWithText("Белый список").assertIsDisplayed()
        composeTestRule.onNodeWithText("Журнал блокировок").assertIsDisplayed()
    }
}

@Composable
private fun CallBlockerScreenPreview() {
    CallBlockerScreen(viewModel = androidx.lifecycle.viewmodel.compose.viewModel())
}