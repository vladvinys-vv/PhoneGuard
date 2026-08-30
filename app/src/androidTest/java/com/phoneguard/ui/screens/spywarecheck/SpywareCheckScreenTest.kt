package com.phoneguard.ui.screens.spywarecheck

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

class SpywareCheckScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun spywareCheckScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SpywareCheckScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Проверка на шпионское ПО").assertIsDisplayed()
    }

    @Test
    fun spywareCheckScreen_displaysScanButton() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SpywareCheckScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Сканировать сейчас").assertIsDisplayed()
    }
}

@Composable
private fun SpywareCheckScreenPreview() {
    SpywareCheckScreen()
}
