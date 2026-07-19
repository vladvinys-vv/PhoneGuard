package com.phoneguard.ui.screens.fullscan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.phoneguard.R
import com.phoneguard.model.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScanScreen(viewModel: FullScanViewModel = hiltViewModel()) {
    val isScanning by viewModel.isScanning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val report by viewModel.report.collectAsState()
    val scanHistory by viewModel.scanHistory.collectAsState()
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.full_scan)) },
                actions = {
                    if (!isScanning) {
                        IconButton(onClick = { viewModel.startScan() }, contentDescription = stringResource(R.string.scan_now)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                    }
                }
            )
        }
    ) { padding ->
        NavHost(navController = navController, startDestination = "fullscan_main") {
            composable("fullscan_main") {
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

                    if (scanHistory.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.scan_history),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(scanHistory) { history ->
                                ScanHistoryCard(
                                    history = history,
                                    onClick = {
                                        navController.navigate("scan_detail/${history.id}")
                                    }
                                )
                            }
                        }
                    }
                }
            }
            composable("scan_detail/{scanId}") { backStackEntry ->
                val scanId = backStackEntry.arguments?.getString("scanId")?.toLongOrNull()
                val scan = scanHistory.find { it.id == scanId }
                if (scan != null) {
                    ScanDetailScreen(scan = scan, onBack = { navController.popBackStack() })
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.scan_not_found))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDetailScreen(scan: ScanHistory, onBack: () -> Unit, viewModel: FullScanViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val exportResult by viewModel.exportPdfResult.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(exportResult) {
        exportResult?.let { path ->
            val message = context.getString(R.string.export_pdf_success, path)
            snackbarHostState.showSnackbar(message)
            viewModel.clearExportResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Scan Report") },
                navigationIcon = {
                    IconButton(onClick = onBack, contentDescription = stringResource(R.string.back)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.exportPdf(scan) }, contentDescription = stringResource(R.string.export_pdf)) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        scan.riskScore >= 80 -> Color(0xFFC8E6C9)
                        scan.riskScore >= 50 -> Color(0xFFFFF9C4)
                        else -> Color(0xFFFFCDD2)
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Risk Score: ${scan.riskScore}/100",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Issues Found: ${scan.issuesFound}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Date: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(scan.timestamp))}")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Report Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = scan.reportJson,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanHistoryCard(history: ScanHistory, onClick: () -> Unit = {}) {
    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val dateText = sdf.format(Date(history.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                history.riskScore >= 80 -> Color(0xFFC8E6C9)
                history.riskScore >= 50 -> Color(0xFFFFF9C4)
                else -> Color(0xFFFFCDD2)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${history.issuesFound} issues",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${history.riskScore}/100",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    history.riskScore >= 80 -> Color(0xFF2E7D32)
                    history.riskScore >= 50 -> Color(0xFFF57F17)
                    else -> Color(0xFFC62828)
                }
            )
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
                        contentDescription = when (issue.severity) {
                            IssueSeverity.CRITICAL -> stringResource(R.string.critical_risk)
                            IssueSeverity.WARNING -> stringResource(R.string.warning)
                            IssueSeverity.INFO -> stringResource(R.string.info)
                        },
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
