package com.phoneguard.ui.screens.vault

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
 * Экран сейфа: заголовок и кнопка разблокировки.
 *
 * Тексты берутся из ресурсов — раньше русские строки были захардкожены в тесте.
 */
class VaultScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun vaultScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                VaultScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.secure_vault))
            .assertIsDisplayed()
    }

    @Test
    fun vaultScreen_displaysUnlockButton() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                VaultScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.vault_unlock))
            .assertIsDisplayed()
    }
}

@Composable
private fun VaultScreenPreview() {
    VaultScreen()
}
