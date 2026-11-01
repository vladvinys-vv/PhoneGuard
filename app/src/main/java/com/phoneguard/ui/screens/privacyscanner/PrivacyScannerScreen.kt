package com.phoneguard.ui.screens.privacyscanner

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phoneguard.R
import com.phoneguard.model.PrivacyApp
import com.phoneguard.util.IntentUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScannerScreen() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "privacy_list"
    ) {
        composable("privacy_list") {
            PrivacyScannerListScreen(
                onNavigateToDetail = { app ->
                    navController.navigate("privacy_detail/${app.packageName}")
                }
            )
        }
        composable("privacy_detail/{packageName}") { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: ""
            PrivacyScannerDetailScreen(
                packageName = packageName,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScannerListScreen(
    onNavigateToDetail: (PrivacyApp) -> Unit,
    viewModel: PrivacyScannerViewModel = hiltViewModel()
) {
    val apps by viewModel.filteredApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentFilter by viewModel.selectedFilter.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_scanner)) },
                actions = {
                    IconButton(onClick = { viewModel.loadApps() }, contentDescription = stringResource(R.string.scan_now)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            FilterRow(
                currentFilter = currentFilter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (apps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.no_apps_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn {
                    items(apps) { app ->
                        PrivacyAppItem(
                            app = app,
                            formatTimestamp = { viewModel.formatTimestamp(it) },
                            onClick = { onNavigateToDetail(app) }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun FilterRow(
    currentFilter: PrivacyFilter,
    onFilterSelected: (PrivacyFilter) -> Unit
) {
    val filters = listOf(
        PrivacyFilter.All to R.string.filter_all,
        PrivacyFilter.Excessive to R.string.filter_excessive,
        PrivacyFilter.RecentlyUsedCamera to R.string.filter_camera,
        PrivacyFilter.RecentlyUsedMic to R.string.filter_mic
    )

    // coerceAtLeast(0): если фильтр когда-нибудь появится в enum, но не в этом списке,
    // indexOfFirst вернёт -1, а ScrollableTabRow с отрицательным индексом падает.
    ScrollableTabRow(selectedTabIndex = filters.indexOfFirst { it.first == currentFilter }.coerceAtLeast(0)) {
        filters.forEachIndexed { index, (filter, labelRes) ->
            Tab(
                selected = currentFilter == filter,
                onClick = { onFilterSelected(filter) },
                text = { Text(stringResource(labelRes)) }
            )
        }
    }
}

@Composable
fun PrivacyAppItem(
    app: PrivacyApp,
    formatTimestamp: (Long) -> String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        leadingContent = { AppIcon(packageName = app.packageName) },
        headlineContent = { Text(app.appName) },
        supportingContent = {
            Text("${stringResource(R.string.dangerous_permissions)}: ${app.dangerousPermissionCount}")
        },
        trailingContent = {
            if (app.hasExcessivePermissions) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    Text(stringResource(R.string.excessive_permissions_short))
                }
            }
        },
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScannerDetailScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    viewModel: PrivacyScannerViewModel = hiltViewModel()
) {
    val apps by viewModel.apps.collectAsState()
    val app = apps.find { it.packageName == packageName }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app?.appName ?: stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, contentDescription = stringResource(R.string.back)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                IntentUtils.open(context, intent)
            }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.open_app_settings))
            }
        }
    ) { padding ->
        if (app == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.app_not_found))
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                item {
                    ListItem(
                        headlineContent = { Text(app.appName) },
                        supportingContent = { Text(stringResource(R.string.package_name, packageName)) },
                        leadingContent = { AppIcon(packageName = packageName) }
                    )
                    Divider()
                }

                items(app.permissions.filter { it.isDangerous }) { permission ->
                    PermissionItem(
                        permission = permission,
                        formatTimestamp = { viewModel.formatTimestamp(it) }
                    )
                    Divider()
                }
            }
        }
    }
}

@Composable
fun PermissionItem(
    permission: com.phoneguard.model.AppPermission,
    formatTimestamp: (Long) -> String
) {
    val permissionName = when (permission.name) {
        android.Manifest.permission.CAMERA -> stringResource(R.string.camera)
        android.Manifest.permission.RECORD_AUDIO -> stringResource(R.string.microphone)
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION -> stringResource(R.string.location)
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS -> stringResource(R.string.contacts)
        else -> permission.name.split(".").lastOrNull() ?: permission.name
    }

    ListItem(
        headlineContent = { Text(permissionName) },
        supportingContent = {
            if (permission.lastUsedTime != null) {
                Text("${stringResource(R.string.last_used)}: ${formatTimestamp(permission.lastUsedTime)}")
            } else {
                Text(stringResource(R.string.never_used))
            }
        }
    )
}

/**
 * Иконка установленного приложения.
 *
 * Раньше на её месте рисовалась одна и та же «шестерёнка», поэтому в списке
 * приложений с опасными разрешениями нельзя было понять, о каком приложении речь.
 * Иконка берётся из `PackageManager` один раз на пакет и декодируется в bitmap;
 * если пакет удалили — показываем нейтральную иконку.
 */
@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX)
                .asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(ICON_DISPLAY_SIZE)
        )
    } else {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = modifier.size(ICON_DISPLAY_SIZE)
        )
    }
}

private const val ICON_SIZE_PX = 96
private val ICON_DISPLAY_SIZE = 40.dp
