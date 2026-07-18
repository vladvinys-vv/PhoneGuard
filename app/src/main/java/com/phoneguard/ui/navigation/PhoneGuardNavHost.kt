package com.phoneguard.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phoneguard.R
import com.phoneguard.ui.screens.*
import com.phoneguard.ui.screens.antitheft.AntiTheftScreen
import com.phoneguard.ui.screens.callblocker.CallBlockerScreen
import com.phoneguard.ui.screens.dashboard.DashboardScreen
import com.phoneguard.ui.screens.dashboard.DashboardViewModel
import com.phoneguard.ui.screens.firewall.FirewallScreen
import com.phoneguard.ui.screens.privacyscanner.PrivacyScannerScreen
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneGuardNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Dashboard.route

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val navigateToRoute: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Screen.Dashboard.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                currentRoute = currentRoute,
                onNavigateTo = navigateToRoute,
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        },
        gesturesEnabled = true,
        modifier = modifier
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                if (drawerState.isClosed) drawerState.open()
                                else drawerState.close()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Открыть меню"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
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
                        onNavigateToSimSwap = { navController.navigate("simswap_history") }
                    )
                }
                composable(Screen.AntiTheft.route) {
                    AntiTheftScreen(viewModel = hiltViewModel())
                }
                composable(Screen.CallBlocker.route) {
                    CallBlockerScreen(viewModel = hiltViewModel())
                }
                composable(Screen.PrivacyScanner.route) {
                    PrivacyScannerScreen(viewModel = hiltViewModel())
                }
                composable(Screen.SpywareCheck.route) {
                    SpywareCheckScreen(viewModel = hiltViewModel())
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
                composable(Screen.Firewall.route) {
                    FirewallScreen()
                }
                composable(Screen.SecureVault.route) {
                    VaultScreen()
                }
                composable(Screen.FullScan.route) {
                    FullScanScreen()
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
}
