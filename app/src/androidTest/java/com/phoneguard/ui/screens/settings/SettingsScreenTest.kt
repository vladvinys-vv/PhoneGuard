package com.phoneguard.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.R
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

/**
 * Экран настроек: заголовок и ключевые секции.
 *
 * Тексты берутся из ресурсов, а не хардкодятся по-русски: на устройстве с
 * английской локалью прежние проверки («Настройки», «Язык») падали, хотя экран
 * был в порядке.
 */
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SettingsScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings))
            .assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysLanguageSection() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SettingsScreenPreview()
            }
        }
        // «Язык» встречается дважды: заголовок секции и подпись выпадающего списка.
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.language))
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysDarkThemeSection() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SettingsScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.dark_theme))
            .assertIsDisplayed()
    }
}

@Composable
private fun SettingsScreenPreview() {
    SettingsScreen(settingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel())
}
