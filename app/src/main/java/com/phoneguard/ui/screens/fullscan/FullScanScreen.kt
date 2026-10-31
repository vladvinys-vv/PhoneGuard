package com.phoneguard.ui.screens.fullscan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
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
import com.phoneguard.R
import com.phoneguard.fullscan.FullScanOrchestrator
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
    val blockedReason by viewModel.scanBlockedReason.collectAsState()
    val scanFailed by viewModel.scanFailed.collectAsState()
    val comparison by viewModel.comparison.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val tooSoonText = stringResource(R.string.scan_too_soon)
    val alreadyRunningText = stringResource(R.string.scan_already_running)
    LaunchedEffect(blockedReason) {
        val message = when (blockedReason) {
            FullScanViewModel.ScanBlockedReason.TOO_SOON -> tooSoonText
            FullScanViewModel.ScanBlockedReason.ALREADY_RUNNING -> alreadyRunningText
            null -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearBlockedReason()
        }
    }

    val scanFailedText = stringResource(R.string.scan_failed)
    LaunchedEffect(scanFailed) {
        if (scanFailed) {
            snackbarHostState.showSnackbar(scanFailedText)
            viewModel.clearScanFailed()
        }
    }

    // Детали открываются внутри экрана: вложенный NavHost на каждое открытие
    // создавал второй контроллер навигации и второй Scaffold с top bar.
    var selectedScan by remember { mutableStateOf<ScanHistory?>(null) }
    val current = selectedScan
    if (current != null) {
        BackHandler { selectedScan = null }
        ScanDetailScreen(scan = current, onBack = { selectedScan = null }, viewModel = viewModel)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.full_scan)) },
                actions = {
                    if (!isScanning) {
                        IconButton(
                            onClick = { viewModel.startScan() },
                            contentDescription = stringResource(R.string.scan_now)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
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
                comparison?.let { cmp ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${if (cmp.riskScoreDiff >= 0) "+" else ""}${cmp.riskScoreDiff} / " +
                            "${if (cmp.issuesDiff >= 0) "+" else ""}${cmp.issuesDiff}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                    items(scanHistory, key = { it.id }) { history ->
                        ScanHistoryCard(
                            history = history,
                            onClick = { selectedScan = history }
                        )
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

    val exportFailed by viewModel.exportPdfFailed.collectAsState()
    LaunchedEffect(exportFailed) {
        if (exportFailed) {
            snackbarHostState.showSnackbar(context.getString(R.string.export_pdf_failed))
            viewModel.clearExportResult()
        }
    }
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
                title = { Text(stringResource(R.string.scan_report_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, contentDescription = stringResource(R.string.back)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.exportPdf(scan) },
                        contentDescription = stringResource(R.string.export_pdf)
                    ) {
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
                        text = stringResource(R.string.risk_label, scan.riskScore),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.issues_found_label, scan.issuesFound))
                    Spacer(modifier = Modifier.height(8.dp))
                    val scanDate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(scan.timestamp))
                    Text(text = stringResource(R.string.scan_date_label, scanDate))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.report_details),
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                    text = stringResource(R.string.issues_short, history.issuesFound),
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
fun ScanProgressCard(progress: FullScanOrchestrator.FullScanState.Progress?) {
    val totalSteps = progress?.totalSteps?.takeIf { it > 0 } ?: 1
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = progress?.let { stringResource(it.stage.titleRes) }
                    ?: stringResource(R.string.scan_in_progress),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (progress?.step?.toFloat() ?: 0f) / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${progress?.step ?: 0}/$totalSteps",
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
                    text = stringResource(R.string.risk_label, report.riskScore),
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
            // Обычная колонка: LazyColumn внутри карточки на экране без
            // прокрутки-родителя ведёт себя непредсказуемо при измерении.
            report.issues.forEachIndexed { index, issue ->
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
                            IssueSeverity.WARNING -> stringResource(R.string.severity_warning)
                            IssueSeverity.INFO -> stringResource(R.string.severity_info)
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
