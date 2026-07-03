package com.phoneguard.ui.screens.spywarecheck

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.RiskLevel
import com.phoneguard.model.SpywareIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpywareCheckScreen(
    viewModel: SpywareCheckViewModel = hiltViewModel()
) {
    val scanResult by viewModel.scanResult.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spyware_check)) },
                actions = {
                    IconButton(onClick = { viewModel.scanForSpyware() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.scan_now))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                RiskScoreCard(scanResult.totalRiskScore)
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                IndicatorSection(
                    title = stringResource(R.string.device_admin_apps),
                    description = stringResource(R.string.device_admin_desc),
                    apps = scanResult.deviceAdminApps,
                    riskLevel = RiskLevel.HIGH,
                    onAppClick = { app ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
                Divider()
            }

            item {
                IndicatorSection(
                    title = stringResource(R.string.accessibility_services),
                    description = stringResource(R.string.accessibility_desc),
                    apps = scanResult.accessibilityServices,
                    riskLevel = RiskLevel.HIGH,
                    onAppClick = { app ->
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )
                Divider()
            }

            item {
                IndicatorSection(
                    title = stringResource(R.string.sideloaded_apps),
                    description = stringResource(R.string.sideloaded_desc),
                    apps = scanResult.sideloadedApps,
                    riskLevel = RiskLevel.MEDIUM,
                    onAppClick = { app ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
                Divider()
            }

            item {
                IndicatorSection(
                    title = stringResource(R.string.hidden_apps),
                    description = stringResource(R.string.hidden_desc),
                    apps = scanResult.hiddenApps,
                    riskLevel = RiskLevel.HIGH,
                    onAppClick = { app ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
fun RiskScoreCard(score: Int) {
    val color = when {
        score < 20 -> com.phoneguard.ui.theme.ScoreExcellent
        score < 50 -> com.phoneguard.ui.theme.ScoreGood
        score < 80 -> com.phoneguard.ui.theme.ScorePoor
        else -> com.phoneguard.ui.theme.ScoreCritical
    }

    val label = when {
        score < 20 -> stringResource(R.string.excellent)
        score < 50 -> stringResource(R.string.good)
        score < 80 -> stringResource(R.string.poor)
        else -> stringResource(R.string.critical)
    }

    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.risk_score), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(score.toString(), style = MaterialTheme.typography.displayMedium, color = color)
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        }
    }
}

@Composable
fun IndicatorSection(
    title: String,
    description: String,
    apps: List<com.phoneguard.model.SpywareApp>,
    riskLevel: RiskLevel,
    onAppClick: (com.phoneguard.model.SpywareApp) -> Unit
) {
    val riskText = when (riskLevel) {
        RiskLevel.LOW -> stringResource(R.string.low_risk)
        RiskLevel.MEDIUM -> stringResource(R.string.medium_risk)
        RiskLevel.HIGH -> stringResource(R.string.high_risk)
        RiskLevel.CRITICAL -> stringResource(R.string.critical_risk)
    }
    val riskColor = when (riskLevel) {
        RiskLevel.LOW -> com.phoneguard.ui.theme.ScoreExcellent
        RiskLevel.MEDIUM -> com.phoneguard.ui.theme.ScoreGood
        RiskLevel.HIGH -> com.phoneguard.ui.theme.ScorePoor
        RiskLevel.CRITICAL -> com.phoneguard.ui.theme.ScoreCritical
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Badge(containerColor = riskColor) {
                Text(riskText)
            }
        }
        Text(description, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (apps.isEmpty()) {
            Text(stringResource(R.string.no_apps_found), style = MaterialTheme.typography.bodyMedium)
        } else {
            apps.forEach { app ->
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(app.appName) },
                    supportingContent = {
                        val indicators = app.indicators.joinToString(", ") {
                            when (it) {
                                SpywareIndicator.DEVICE_ADMIN -> stringResource(R.string.device_admin)
                                SpywareIndicator.ACCESSIBILITY_SERVICE -> stringResource(R.string.accessibility_services)
                                SpywareIndicator.SIDELOADED -> stringResource(R.string.sideloaded)
                                SpywareIndicator.HIDDEN_LAUNCHER -> stringResource(R.string.hidden)
                            }
                        }
                        Text(indicators)
                    },
                    leadingContent = {
                        androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = null)
                    },
                    onClick = { onAppClick(app) }
                )
                Divider()
            }
        }
    }
}
