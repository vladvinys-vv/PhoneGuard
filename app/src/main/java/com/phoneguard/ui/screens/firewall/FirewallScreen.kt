package com.phoneguard.ui.screens.firewall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.FirewallRule
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.drawable.toBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(
    viewModel: FirewallViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPrepareResult(result.resultCode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.firewall)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Vpn Toggle
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            stringResource(R.string.firewall),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isVpnActive) stringResource(R.string.firewall_active)
                            else stringResource(R.string.firewall_monitoring),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isVpnActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            if (isVpnActive) {
                        viewModel.stopVpn()
                    } else {
                        val vpnIntent = viewModel.prepareVpn()
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent)
                        } else {
                            viewModel.onVpnPrepared()
                        }
                    }
                        }
                    ) {
                        Text(
                            if (isVpnActive) stringResource(R.string.firewall_stop)
                            else stringResource(R.string.firewall_start)
                        )
                    }
                }
            }

            // Apps List
            if (apps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
                    items(apps) { app ->
                        AppFirewallItem(
                            appInfo = app,
                            onRuleChanged = { rule ->
                                viewModel.upsertRule(rule)
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun AppFirewallItem(
    appInfo: AppInfo,
    onRuleChanged: (FirewallRule) -> Unit
) {
    val defaultRule = FirewallRule(
        packageName = appInfo.packageName,
        appName = appInfo.appName
    )
    val rule = appInfo.rule ?: defaultRule

    var blockAll by remember { mutableStateOf(rule.blockAll) }
    var blockWifi by remember { mutableStateOf(rule.blockWifi) }
    var blockMobile by remember { mutableStateOf(rule.blockMobile) }
    var allowWifiOnly by remember { mutableStateOf(rule.allowWifiOnly) }
    var allowMobileOnly by remember { mutableStateOf(rule.allowMobileOnly) }
    var blockBackground by remember { mutableStateOf(rule.blockBackground) }

    fun updateRule() {
        onRuleChanged(
            rule.copy(
                blockAll = blockAll,
                blockWifi = blockWifi,
                blockMobile = blockMobile,
                allowWifiOnly = allowWifiOnly,
                allowMobileOnly = allowMobileOnly,
                blockBackground = blockBackground,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    ListItem(
        leadingContent = {
            Image(
                bitmap = appInfo.icon.toBitmap().asImageBitmap(),
                contentDescription = appInfo.appName,
                modifier = Modifier.size(48.dp)
            )
        },
        headlineContent = { Text(appInfo.appName) },
        supportingContent = {
            Column {
                Text(
                    appInfo.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    FilterChip(
                        selected = blockAll,
                        onClick = {
                            blockAll = !blockAll
                            if (blockAll) {
                                blockWifi = true
                                blockMobile = true
                                allowWifiOnly = false
                                allowMobileOnly = false
                                blockBackground = false
                            }
                            updateRule()
                        },
                        label = { Text("Block All") },
                        leadingIcon = if (blockAll) {
                            { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                        } else null
                    )
                    FilterChip(
                        selected = blockWifi,
                        onClick = {
                            blockWifi = !blockWifi
                            if (blockWifi) allowWifiOnly = false
                            updateRule()
                        },
                        label = { Text("Wi-Fi") },
                        leadingIcon = if (blockWifi) {
                            { Icon(Icons.Default.Wifi, contentDescription = null) }
                        } else null,
                        enabled = !blockAll
                    )
                    FilterChip(
                        selected = blockMobile,
                        onClick = {
                            blockMobile = !blockMobile
                            if (blockMobile) allowMobileOnly = false
                            updateRule()
                        },
                        label = { Text("Mobile") },
                        leadingIcon = if (blockMobile) {
                            { Icon(Icons.Default.Info, contentDescription = null) }
                        } else null,
                        enabled = !blockAll
                    )
                }
                // Additional modes
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    FilterChip(
                        selected = allowWifiOnly,
                        onClick = {
                            allowWifiOnly = !allowWifiOnly
                            if (allowWifiOnly) {
                                blockWifi = false
                                allowMobileOnly = false
                            }
                            updateRule()
                        },
                        label = { Text("Wi-Fi Only") },
                        enabled = !blockAll
                    )
                    FilterChip(
                        selected = allowMobileOnly,
                        onClick = {
                            allowMobileOnly = !allowMobileOnly
                            if (allowMobileOnly) {
                                blockMobile = false
                                allowWifiOnly = false
                            }
                            updateRule()
                        },
                        label = { Text("Mobile Only") },
                        enabled = !blockAll
                    )
                    FilterChip(
                        selected = blockBackground,
                        onClick = {
                            blockBackground = !blockBackground
                            updateRule()
                        },
                        label = { Text("No Background") },
                        enabled = !blockAll
                    )
                }
            }
        }
    )
}
