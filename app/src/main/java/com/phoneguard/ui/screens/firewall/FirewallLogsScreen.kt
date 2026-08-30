package com.phoneguard.ui.screens.firewall

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.FirewallLog
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallLogsScreen(
    onBack: () -> Unit,
    viewModel: FirewallViewModel = hiltViewModel()
) {
    val logs by viewModel.allLogs.collectAsState(initial = emptyList())
    val sdf = remember { SimpleDateFormat("HH:mm:ss dd.MM", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.firewall_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, contentDescription = stringResource(R.string.back)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        Text(
                            stringResource(R.string.log_entries_count, logs.size),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_blocked_connections), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp)
            ) {
                items(logs) { log ->
                    FirewallLogItem(log, sdf)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun FirewallLogItem(log: FirewallLog, sdf: SimpleDateFormat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    log.appName.takeLast(40),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Badge {
                    Text(
                        when (log.connectionType) {
                            FirewallLog.ConnectionType.WIFI -> stringResource(R.string.connection_type_wifi)
                            FirewallLog.ConnectionType.MOBILE -> stringResource(R.string.connection_type_mobile)
                            else -> stringResource(R.string.connection_type_unknown)
                        }
                    )
                }
            }
            Text(
                "${log.ipAddress ?: log.domainName ?: stringResource(R.string.unknown)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            sdf.format(Date(log.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
