package com.phoneguard.ui.screens.spywarecheck

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.R
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

/**
 * Экран проверки шпионского ПО.
 *
 * Кнопка сканирования — иконка с `contentDescription`, поэтому ищем её через
 * `onNodeWithContentDescription` (раньше искали текст «Сканировать сейчас»,
 * которого на экране нет, и тест всегда падал).
 */
class SpywareCheckScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun spywareCheckScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SpywareCheckScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.spyware_check))
            .assertIsDisplayed()
    }

    @Test
    fun spywareCheckScreen_displaysScanButton() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                SpywareCheckScreenPreview()
            }
        }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.scan_now))
            .assertIsDisplayed()
    }
}

@Composable
private fun SpywareCheckScreenPreview() {
    SpywareCheckScreen()
}
