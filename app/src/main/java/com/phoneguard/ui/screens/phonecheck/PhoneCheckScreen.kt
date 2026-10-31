package com.phoneguard.ui.screens.phonecheck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.deviceaudit.AuditCategory
import com.phoneguard.deviceaudit.AuditItem
import com.phoneguard.deviceaudit.AuditStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «чек всего телефона».
 *
 * Показывает оценку состояния (0..100) и подробный список проверок по группам:
 * безопасность, сеть, память, приложения и система.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCheckScreen(
    viewModel: PhoneCheckViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null
) {
    val report by viewModel.report.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val hasError by viewModel.error.collectAsState()
    val savedTime by viewModel.savedTime.collectAsState()
    val savedScore by viewModel.savedScore.collectAsState()

    val timestamp = report?.timestamp ?: savedTime
    val lastRunText = if (timestamp == null) {
        stringResource(R.string.phone_check_never_run)
    } else {
        val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date(timestamp))
        stringResource(R.string.phone_check_last_run, formatted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.phone_check)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Если проверки ещё не было, нельзя показывать «0/100» красным:
                    // это выглядело как критическое состояние устройства.
                    val score: Int? = report?.score ?: savedScore
                    if (score != null) {
                        Text(
                            text = stringResource(R.string.phone_check_score, score),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor(score)
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.phone_check_never_run),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (report != null) {
                        Text(
                            text = stringResource(
                                R.string.phone_check_summary,
                                report.items.size,
                                report.issueCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = lastRunText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (hasError) {
                        Text(
                            text = stringResource(R.string.error_generic),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.runAudit() },
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.phone_check_running))
                        } else {
                            Text(stringResource(R.string.phone_check_run))
                        }
                    }
                }
            }

            val items = report?.items.orEmpty()
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.phone_check_never_run))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AuditCategory.entries.forEach { category ->
                        val categoryItems = items.filter { it.category == category }
                        if (categoryItems.isEmpty()) return@forEach
                        item(key = "header_${category.name}") {
                            Text(
                                text = stringResource(category.titleRes()),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(categoryItems, key = { it.id }) { item ->
                            AuditItemRow(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditItemRow(item: AuditItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(item.status.color().copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.status.icon(),
                    contentDescription = stringResource(item.status.labelRes()),
                    tint = item.status.color(),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                val detail = if (item.detailArgs.isEmpty()) {
                    stringResource(item.detailRes)
                } else {
                    stringResource(item.detailRes, *item.detailArgs.toTypedArray())
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                item.recommendationRes?.let { recommendation ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(recommendation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** Цвет оценки: зелёный ≥ 80, жёлтый ≥ 50, иначе красный. */
@Composable
private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Color(0xFF2E7D32)
    score >= 50 -> Color(0xFFF9A825)
    else -> MaterialTheme.colorScheme.error
}

private fun AuditStatus.labelRes(): Int = when (this) {
    AuditStatus.OK -> R.string.audit_status_ok
    AuditStatus.INFO -> R.string.audit_status_info
    AuditStatus.WARNING -> R.string.audit_status_warning
    AuditStatus.CRITICAL -> R.string.audit_status_critical
}

private fun AuditStatus.icon(): ImageVector = when (this) {
    AuditStatus.OK -> Icons.Default.CheckCircle
    AuditStatus.INFO -> Icons.Default.Info
    AuditStatus.WARNING -> Icons.Default.Warning
    AuditStatus.CRITICAL -> Icons.Default.Error
}

@Composable
private fun AuditStatus.color(): Color = when (this) {
    AuditStatus.OK -> Color(0xFF2E7D32)
    AuditStatus.INFO -> MaterialTheme.colorScheme.primary
    AuditStatus.WARNING -> Color(0xFFF9A825)
    AuditStatus.CRITICAL -> MaterialTheme.colorScheme.error
}

private fun AuditCategory.titleRes(): Int = when (this) {
    AuditCategory.SECURITY -> R.string.audit_category_security
    AuditCategory.NETWORK -> R.string.audit_category_network
    AuditCategory.STORAGE -> R.string.audit_category_storage
    AuditCategory.APPS -> R.string.audit_category_apps
    AuditCategory.SYSTEM -> R.string.audit_category_system
}
