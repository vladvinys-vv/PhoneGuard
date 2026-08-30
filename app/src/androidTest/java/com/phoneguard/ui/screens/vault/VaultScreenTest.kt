package com.phoneguard.ui.screens.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

class VaultScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun vaultScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                VaultScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Защищённое хранилище").assertIsDisplayed()
    }

    @Test
    fun vaultScreen_displaysUnlockButton() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                VaultScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Разблокировать хранилище").assertIsDisplayed()
    }
}

@Composable
private fun VaultScreenPreview() {
    VaultScreen()
}
