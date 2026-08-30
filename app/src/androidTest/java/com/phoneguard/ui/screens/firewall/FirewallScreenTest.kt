package com.phoneguard.ui.screens.firewall

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

class FirewallScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun firewallScreen_displaysTitle() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                FirewallScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("Firewall").assertIsDisplayed()
    }

    @Test
    fun firewallScreen_displaysMvpNotice() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                FirewallScreenPreview()
            }
        }
        composeTestRule.onNodeWithText("MVP режим").assertIsDisplayed()
    }
}

@Composable
private fun FirewallScreenPreview() {
    FirewallScreen(viewModel = androidx.lifecycle.viewmodel.compose.viewModel())
}