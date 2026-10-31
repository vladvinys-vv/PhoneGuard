package com.phoneguard.ui.screens.firewall

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.FirewallRule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(
    onOpenLogs: () -> Unit = {},
    viewModel: FirewallViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsState()
    val logs by viewModel.allLogs.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()
    val vpnStartError by viewModel.vpnStartError.collectAsState()

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
                    // Раньше полный журнал брандмауэра (FirewallLogsScreen) был реализован,
                    // но недостижим: на экране показывались только последние 20 записей.
                    IconButton(
                        onClick = onOpenLogs,
                        contentDescription = stringResource(R.string.firewall_logs_title)
                    ) {
                        Icon(Icons.Default.List, contentDescription = null)
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
                            text = stringResource(R.string.firewall_limited_mode_badge),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(R.string.firewall_limited_mode_notice),
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
                vpnStartError?.let { errorRes ->
                    Text(
                        text = stringResource(errorRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
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
                    text = stringResource(R.string.firewall_recent_blocks),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs.take(20), key = { log -> log.id }) { log ->
                        ListItem(
                            headlineContent = { Text(log.appName) },
                            supportingContent = {
                                val target = log.ipAddress ?: log.domainName.orEmpty()
                                Text(
                                    "$target • ${trafficDirectionLabel(log.trafficDirection)} • " +
                                        connectionTypeLabel(log.connectionType)
                                )
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
private fun AppFirewallItem(
    appInfo: AppInfo,
    onRuleChanged: (FirewallRule) -> Unit
) {
    // Состояние должно быть Compose-состоянием, иначе чипы не перерисовываются
    // после переключения (раньше это были обычные var).
    var rule by remember(appInfo.packageName, appInfo.rule) { mutableStateOf(appInfo.rule) }
    var showDomainDialog by remember { mutableStateOf(false) }

    fun persist(newRule: FirewallRule) {
        rule = newRule
        onRuleChanged(newRule)
    }

    fun baseRule(): FirewallRule = rule ?: FirewallRule(
        packageName = appInfo.packageName,
        appName = appInfo.appName,
        allowByDefault = true
    )

    fun update(transform: (FirewallRule) -> FirewallRule) {
        persist(transform(baseRule()).copy(updatedAt = System.currentTimeMillis()))
    }

    val blockAll = rule?.blockAll == true
    val blockWifi = rule?.blockWifi == true
    val blockMobile = rule?.blockMobile == true

    if (showDomainDialog) {
        var domainsText by remember {
            mutableStateOf(jsonListToText(rule?.blockedDomains))
        }
        var ipsText by remember {
            mutableStateOf(jsonListToText(rule?.blockedIps))
        }

        AlertDialog(
            onDismissRequest = { showDomainDialog = false },
            title = { Text(stringResource(R.string.block_domains_ips_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = domainsText,
                        onValueChange = { domainsText = it },
                        label = { Text(stringResource(R.string.block_domains_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ipsText,
                        onValueChange = { ipsText = it },
                        label = { Text(stringResource(R.string.block_ips_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.firewall_lists_not_enforced_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    update {
                        it.copy(
                            blockedDomains = textToJsonList(domainsText),
                            blockedIps = textToJsonList(ipsText)
                        )
                    }
                    showDomainDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDomainDialog = false }) {
                    Text(stringResource(R.string.cancel))
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
                painter = rememberVectorPainter(appInfo.icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { showDomainDialog = true },
                    contentDescription = stringResource(R.string.edit_domains_ips)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                }
                FilterChip(
                    selected = blockAll,
                    onClick = {
                        update {
                            it.copy(
                                blockAll = !blockAll,
                                blockWifi = !blockAll,
                                blockMobile = !blockAll,
                                allowByDefault = blockAll
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.block_all)) },
                    leadingIcon = if (blockAll) {
                        { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                    } else null
                )
                FilterChip(
                    selected = blockWifi,
                    onClick = {
                        update {
                            it.copy(
                                blockWifi = !blockWifi,
                                allowWifiOnly = blockWifi,
                                allowMobileOnly = if (!blockWifi) false else it.allowMobileOnly
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.wifi)) },
                    leadingIcon = if (blockWifi) {
                        { Icon(Icons.Default.Wifi, contentDescription = null) }
                    } else null,
                    enabled = !blockAll
                )
                FilterChip(
                    selected = blockMobile,
                    onClick = {
                        update {
                            it.copy(
                                blockMobile = !blockMobile,
                                allowMobileOnly = blockMobile,
                                allowWifiOnly = if (!blockMobile) false else it.allowWifiOnly
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.mobile_data)) },
                    leadingIcon = if (blockMobile) {
                        { Icon(Icons.Default.Info, contentDescription = null) }
                    } else null,
                    enabled = !blockAll
                )
            }
        }
    )
}

/** `["a","b"]` → `a, b` для редактирования в текстовом поле. */
private fun jsonListToText(json: String?): String {
    if (json.isNullOrBlank()) return ""
    return try {
        val array = org.json.JSONArray(json)
        (0 until array.length()).joinToString(", ") { array.optString(it) }
    } catch (_: Exception) {
        json.removeSurrounding("[", "]").replace("\"", "")
    }
}

/** `a, b` → `["a","b"]` (формат, который читает [com.phoneguard.firewall.PhoneGuardVpnService]). */
private fun textToJsonList(text: String): String {
    val array = org.json.JSONArray()
    text.split(",", " ", "\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .forEach { array.put(it) }
    return array.toString()
}
