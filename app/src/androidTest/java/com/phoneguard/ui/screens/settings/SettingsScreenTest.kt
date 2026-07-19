package com.phoneguard.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settingsScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SettingsScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Настройки").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysLanguageSection() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SettingsScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Язык").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysDarkThemeSection() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SettingsScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Тёмная тема").assertIsDisplayed()
    }
}

@Composable
private fun SettingsScreenPreview() {
    SettingsScreen(settingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel())
}