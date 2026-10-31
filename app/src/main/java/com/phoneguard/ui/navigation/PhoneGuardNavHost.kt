package com.phoneguard.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.phoneguard.R
import com.phoneguard.ui.screens.antitheft.AntiTheftScreen
import com.phoneguard.ui.screens.callblocker.CallBlockerScreen
import com.phoneguard.ui.screens.dashboard.DashboardScreen
import com.phoneguard.ui.screens.firewall.FirewallScreen
import com.phoneguard.ui.screens.fullscan.FullScanScreen
import com.phoneguard.ui.screens.phonecheck.PhoneCheckScreen
import com.phoneguard.ui.screens.privacyscanner.PrivacyScannerScreen
import com.phoneguard.ui.screens.settings.SettingsScreen
import com.phoneguard.ui.screens.spywarecheck.SpywareCheckScreen
import com.phoneguard.ui.screens.vault.VaultScreen
import com.phoneguard.ui.screens.simswap.SimSwapConfirmationScreen
import com.phoneguard.ui.screens.simswap.SimSwapHistoryScreen

sealed class Screen(val route: String, val title: Int, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", R.string.dashboard, Icons.Default.Home)
    object AntiTheft : Screen("antitheft", R.string.anti_theft, Icons.Default.Lock)
    object CallBlocker : Screen("callblocker", R.string.call_sms_blocker, Icons.Default.Block)
    object PrivacyScanner : Screen("privacy", R.string.privacy_scanner, Icons.Default.VisibilityOff)
    object SpywareCheck : Screen("spyware", R.string.spyware_check, Icons.Default.Search)
    object Settings : Screen("settings", R.string.settings, Icons.Default.Settings)
    object Firewall : Screen("firewall", R.string.firewall, Icons.Default.Security)
    object SecureVault : Screen("vault", R.string.secure_vault, Icons.Default.Lock)
    object FullScan : Screen("fullscan", R.string.full_scan, Icons.Default.Security)
    object PhoneCheck : Screen("phonecheck", R.string.phone_check, Icons.Default.PhonelinkSetup)
}

/**
 * @param deepLinkIntent intent, которым открыли Activity (`phoneguard://…` из ярлыка
 *        или извне). Раньше ссылки были объявлены в графе (`navDeepLink`), но никто
 *        не вызывал `handleDeepLink`, поэтому ярлыки на рабочем столе открывали
 *        дашборд вместо нужного экрана.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneGuardNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    deepLinkIntent: Intent? = null
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Dashboard.route

    LaunchedEffect(deepLinkIntent, navController) {
        val intent = deepLinkIntent ?: return@LaunchedEffect
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            navController.handleDeepLink(intent)
        }
    }

    val showBottomBar = bottomNavItems.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                PhoneGuardBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Screen.Dashboard.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        viewModel = hiltViewModel(),
                        onNavigateToAntiTheft = { navController.navigate(Screen.AntiTheft.route) },
                        onNavigateToCallBlocker = { navController.navigate(Screen.CallBlocker.route) },
                        onNavigateToPrivacyScanner = { navController.navigate(Screen.PrivacyScanner.route) },
                        onNavigateToSpywareCheck = { navController.navigate(Screen.SpywareCheck.route) },
                        onNavigateToFullScan = { navController.navigate(Screen.FullScan.route) },
                        onNavigateToSimSwap = { navController.navigate("simswap_history") },
                        onNavigateToPhoneCheck = { navController.navigate(Screen.PhoneCheck.route) }
                    )
                }
                composable(
                    route = Screen.AntiTheft.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://antitheft" })
                ) {
                    AntiTheftScreen(viewModel = hiltViewModel())
                }
                composable(
                    route = Screen.CallBlocker.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://callblocker" })
                ) {
                    CallBlockerScreen(viewModel = hiltViewModel())
                }
                composable(
                    route = Screen.PrivacyScanner.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://privacy" })
                ) {
                    PrivacyScannerScreen()
                }
                composable(
                    route = Screen.SpywareCheck.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://spyware" })
                ) {
                    SpywareCheckScreen(viewModel = hiltViewModel())
                }
                composable(
                    route = Screen.Settings.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://settings" })
                ) {
                    SettingsScreen(
                        settingsViewModel = hiltViewModel(),
                        onNavigateToPhoneCheck = { navController.navigate(Screen.PhoneCheck.route) }
                    )
                }
                composable(
                    route = Screen.Firewall.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://firewall" })
                ) {
                    FirewallScreen(onOpenLogs = { navController.navigate("firewall_logs") })
                }
                composable("firewall_logs") {
                    com.phoneguard.ui.screens.firewall.FirewallLogsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Screen.SecureVault.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://vault" })
                ) {
                    VaultScreen()
                }
                composable(
                    route = Screen.FullScan.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://fullscan" })
                ) {
                    FullScanScreen()
                }
                composable(
                    route = Screen.PhoneCheck.route,
                    deepLinks = listOf(navDeepLink { uriPattern = "phoneguard://phonecheck" })
                ) {
                    PhoneCheckScreen(onBack = { navController.popBackStack() })
                }
                composable("simswap") {
                    com.phoneguard.ui.screens.simswap.SimSwapConfirmationScreen(
                        onConfirmed = { navController.popBackStack() },
                        onRejected = { navController.popBackStack() }
                    )
                }
                composable("simswap_history") {
                    com.phoneguard.ui.screens.simswap.SimSwapHistoryScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("simswap_onboarding") {
                    com.phoneguard.ui.screens.simswap.SimSwapOnboardingScreen(
                        onGetStarted = { navController.popBackStack() },
                        onSkip = { navController.popBackStack() }
                    )
                }
            }
        }
    }
