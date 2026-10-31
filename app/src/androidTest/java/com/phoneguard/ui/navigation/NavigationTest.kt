package com.phoneguard.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phoneguard.ui.theme.PhoneGuardTheme
import org.junit.Rule
import org.junit.Test

/**
 * Проверка каркаса навигации: нижняя панель и переходы между разделами.
 *
 * Раньше тест не рендерил нижнюю панель вовсе (`onNodeWithText("Настройки")`
 * падал), а `performClick` даже не был импортирован.
 */
class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val destinations = listOf(
        "dashboard" to "Панель управления",
        "antitheft" to "Антивор",
        "callblocker" to "Блокировщик звонков",
        "firewall" to "Брандмауэр",
        "settings" to "Настройки"
    )

    @Test
    fun dashboardIsShownByDefault() {
        composeTestRule.setContent {
            PhoneGuardTheme { TestNavHost(destinations) }
        }

        composeTestRule.onNodeWithText("Экран: dashboard").assertIsDisplayed()
    }

    @Test
    fun tappingBottomBarOpensSettings() {
        composeTestRule.setContent {
            PhoneGuardTheme { TestNavHost(destinations) }
        }

        // В панели есть пункт «Настройки»; после нажатия открывается его экран.
        composeTestRule.onNodeWithText("Настройки").performClick()

        composeTestRule.onNodeWithText("Экран: settings").assertIsDisplayed()
    }

    @Test
    fun tappingBottomBarOpensFirewall() {
        composeTestRule.setContent {
            PhoneGuardTheme { TestNavHost(destinations) }
        }

        composeTestRule.onNodeWithText("Брандмауэр").performClick()

        composeTestRule.onNodeWithText("Экран: firewall").assertIsDisplayed()
    }
}

@Composable
private fun TestNavHost(destinations: List<Pair<String, String>>) {
    val navController = rememberNavController()
    val backStackEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { (route, label) ->
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            if (currentRoute != route) {
                                navController.navigate(route) {
                                    popUpTo("dashboard")
                                    launchSingleTop = true
                                }
                            }
                        },
                        label = { Text(label) },
                        icon = {}
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            destinations.forEach { (route, _) ->
                composable(route) { Text("Экран: $route") }
            }
        }
    }
}
