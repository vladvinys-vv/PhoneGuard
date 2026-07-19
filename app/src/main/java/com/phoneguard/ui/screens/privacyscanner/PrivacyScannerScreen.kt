package com.phoneguard.ui.screens.privacyscanner

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phoneguard.R
import com.phoneguard.model.PrivacyApp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter

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

    ScrollableTabRow(selectedTabIndex = filters.indexOfFirst { it.first == currentFilter }) {
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
        leadingContent = {
            // Drawable to painter would be here; for simplicity we use placeholder
            // We'll implement it later or use a placeholder icon
            androidx.compose.material3.Icon(Icons.Default.Settings, contentDescription = null)
        },
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
                    IconButton(onClick = onNavigateBack, contentDescription = "Back") {
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
                context.startActivity(intent)
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
                        leadingContent = {
                            // App icon placeholder again
                            androidx.compose.material3.Icon(Icons.Default.Settings, contentDescription = null)
                        }
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
