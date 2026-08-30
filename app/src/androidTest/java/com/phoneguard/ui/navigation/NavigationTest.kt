package com.phoneguard.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bottomNavigation_dashboardIsSelected() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                TestNavHost()
            }
        }
        composeTestRule.onNodeWithText("Панель управления").assertIsDisplayed()
    }

    @Test
    fun bottomNavigation_navigateToSettings() {
        composeTestRule.setContent {
            PhoneGuardTheme {
                TestNavHost()
            }
        }
        composeTestRule.onNodeWithText("Настройки").performClick()
        composeTestRule.onNodeWithText("Настройки").assertIsDisplayed()
    }
}

@Composable
private fun TestNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            androidx.compose.material3.Text("Панель управления")
        }
        composable("antitheft") {
            androidx.compose.material3.Text("Антивор")
        }
        composable("callblocker") {
            androidx.compose.material3.Text("Блокировщик звонков & SMS")
        }
        composable("firewall") {
            androidx.compose.material3.Text("Firewall")
        }
        composable("settings") {
            androidx.compose.material3.Text("Настройки")
        }
    }
}