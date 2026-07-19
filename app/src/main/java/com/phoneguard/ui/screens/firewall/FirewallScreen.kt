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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.FirewallRule
import com.phoneguard.model.FirewallLog
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
    val logs by viewModel.allLogs.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPrepareResult(result.resultCode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.firewall)) },
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
            // MVP Limitation Notice
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text(
                            text = "MVP режим",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Фаерволл работает в ограниченном режиме. Блокировка на уровне приложений не обеспечивает 100% защиту без root-доступа.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.no_apps_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

            // Recent Logs
            if (logs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recent Blocks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs.take(20)) { log ->
                        ListItem(
                            headlineContent = { Text(log.appName) },
                            supportingContent = {
                                Text("${log.ipAddress ?: log.domainName} • ${log.trafficDirection.name} • ${log.connectionType.name}")
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    )
}

@Composable
private fun AppFirewallItem(
    appInfo: com.phoneguard.ui.screens.firewall.AppInfo,
    onRuleChanged: (com.phoneguard.model.FirewallRule) -> Unit
) {
    var rule by remember { mutableStateOf(appInfo.rule) }
    var showDomainDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val blockWifi = rule?.blockWifi ?: false
    val blockMobile = rule?.blockMobile ?: false
    val blockAll = rule?.blockAll ?: false
    val allowWifiOnly = rule?.allowWifiOnly ?: false
    val allowMobileOnly = rule?.allowMobileOnly ?: false
    val blockBackground = rule?.blockBackground ?: false

    fun updateRule() {
        val newRule = (rule ?: com.phoneguard.model.FirewallRule(
            packageName = appInfo.packageName,
            appName = appInfo.appName,
            blockWifi = false,
            blockMobile = false,
            blockAll = false,
            blockVpn = false,
            blockBackground = false,
            blockedDomains = "[]",
            blockedIps = "[]",
            allowByDefault = true,
            allowWifiOnly = false,
            allowMobileOnly = false,
            updatedAt = System.currentTimeMillis()
        )).copy(
            blockWifi = blockWifi,
            blockMobile = blockMobile,
            blockAll = blockAll,
            allowWifiOnly = allowWifiOnly,
            allowMobileOnly = allowMobileOnly,
            blockBackground = blockBackground,
            updatedAt = System.currentTimeMillis()
        )
        rule = newRule
        onRuleChanged(newRule)
    }

    if (showDomainDialog) {
        var domainsText by remember { mutableStateOf(rule?.blockedDomains?.removeSurrounding("[", "]")?.replace("\"", "") ?: "") }
        var ipsText by remember { mutableStateOf(rule?.blockedIps?.removeSurrounding("[", "]")?.replace("\"", "") ?: "") }

        AlertDialog(
            onDismissRequest = { showDomainDialog = false },
            title = { Text("Block domains/IPs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = domainsText,
                        onValueChange = { domainsText = it },
                        label = { Text("Domains (comma separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ipsText,
                        onValueChange = { ipsText = it },
                        label = { Text("IPs (comma separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newRule = (rule ?: com.phoneguard.model.FirewallRule(
                        packageName = appInfo.packageName,
                        appName = appInfo.appName,
                        blockWifi = false,
                        blockMobile = false,
                        blockAll = false,
                        blockVpn = false,
                        blockBackground = false,
                        blockedDomains = "[]",
                        blockedIps = "[]",
                        allowByDefault = true,
                        allowWifiOnly = false,
                        allowMobileOnly = false,
                        updatedAt = System.currentTimeMillis()
                    )).copy(
                        blockedDomains = domainsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toString(),
                        blockedIps = ipsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toString(),
                        updatedAt = System.currentTimeMillis()
                    )
                    rule = newRule
                    onRuleChanged(newRule)
                    showDomainDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDomainDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(appInfo.appName) },
        supportingContent = { Text(appInfo.packageName) },
        leadingContent = {
            Icon(
                painter = rememberVectorPainter(Icons.Default.Security),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { showDomainDialog = true }, contentDescription = "Edit domains/IPs") {
                    Icon(Icons.Default.Edit, contentDescription = null)
                }
                FilterChip(
                    selected = blockAll,
                    onClick = {
                        val newBlockAll = !blockAll
                        if (newBlockAll) {
                            onRuleChanged(
                                com.phoneguard.model.FirewallRule(
                                    packageName = appInfo.packageName,
                                    appName = appInfo.appName,
                                    blockWifi = true,
                                    blockMobile = true,
                                    blockAll = true,
                                    blockVpn = false,
                                    blockBackground = true,
                                    blockedDomains = "[]",
                                    blockedIps = "[]",
                                    allowByDefault = false,
                                    allowWifiOnly = false,
                                    allowMobileOnly = false,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        } else {
                            updateRule()
                        }
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
        }
    )
}

