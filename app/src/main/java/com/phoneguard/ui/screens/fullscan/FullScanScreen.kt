package com.phoneguard.ui.screens.fullscan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScanScreen(viewModel: FullScanViewModel = hiltViewModel()) {
    val isScanning by viewModel.isScanning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val report by viewModel.report.collectAsState()
    val scanHistory by viewModel.scanHistory.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.full_scan)) },
                actions = {
                    if (!isScanning) {
                        IconButton(onClick = { viewModel.startFullScan() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start Scan")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (isScanning) {
                ScanProgressCard(progress)
            }

            report?.let { r ->
                ScanReportCard(report = r)
            }

            scanHistory?.let { history ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Последнее сканирование: ${history.timestamp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ScanProgressCard(progress: FullScanProgress?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                progress?.stageName ?: "Сканирование...",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (progress?.step?.toFloat() ?: 0f) / (progress?.totalSteps?.toFloat() ?: 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${progress?.step ?: 0}/${progress?.totalSteps ?: 0}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ScanReportCard(report: FullScanReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                report.riskScore >= 80 -> Color(0xFFC8E6C9)
                report.riskScore >= 50 -> Color(0xFFFFF9C4)
                else -> Color(0xFFFFCDD2)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Риск: ${report.riskScore}/100",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(
                    imageVector = when {
                        report.criticalCount > 0 -> Icons.Default.Error
                        report.warningCount > 0 -> Icons.Default.Warning
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = when {
                        report.criticalCount > 0 -> Color.Red
                        report.warningCount > 0 -> Color(0xFFFFA000)
                        else -> Color.Green
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn {
                items(report.issues.size) { index ->
                    val issue = report.issues[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (issue.severity) {
                                IssueSeverity.CRITICAL -> Icons.Default.Error
                                IssueSeverity.WARNING -> Icons.Default.Warning
                                IssueSeverity.INFO -> Icons.Default.Info
                            },
                            contentDescription = null,
                            tint = when (issue.severity) {
                                IssueSeverity.CRITICAL -> Color.Red
                                IssueSeverity.WARNING -> Color(0xFFFFA000)
                                IssueSeverity.INFO -> Color.Gray
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(issue.title, fontWeight = FontWeight.Medium)
                            Text(
                                issue.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (index < report.issues.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
