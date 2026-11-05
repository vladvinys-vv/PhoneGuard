package com.phoneguard.ui.screens.protect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.protect.SensitiveAppGuard
import com.phoneguard.protect.SensitiveAppPolicy

/**
 * «Защита приложений»: что пользователь считает ценным и кто рядом умеет подглядывать.
 *
 * Раньше это были только находки сканера — список приложений с правом поверх окон, без
 * связи с банком или мессенджером пользователя. Здесь две половины сведены вместе, поэтому
 * предупреждение получает адресата: «вот приложение, вот что оно умеет, вот почему это
 * важно рядом с вашим банком».
 *
 * Чего экран не делает и не обещает: он не блокирует чужое приложение. Оверлей-атаку
 * со стороны обычного приложения Android запретить не даёт — можно только показать риск и
 * предложить убрать право в системных настройках.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensitiveAppsScreen(
    viewModel: SensitiveAppsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val limitReached by viewModel.limitReached.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sensitive_apps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                SensitiveAppsViewModel.SensitiveAppsState.SensitiveAppsLoading ->
                    CenteredText(stringResource(R.string.sensitive_apps_loading))
                SensitiveAppsViewModel.SensitiveAppsState.SensitiveAppsUnavailable ->
                    CenteredText(stringResource(R.string.sensitive_apps_unavailable))
                is SensitiveAppsViewModel.SensitiveAppsState.SensitiveAppsReady ->
                    SensitiveAppsContent(
                        snapshot = current.snapshot,
                        limitReached = limitReached,
                        maxProtected = viewModel.maxProtected,
                        onToggle = viewModel::setProtected,
                        onLimitConsumed = viewModel::consumeLimitReached
                    )
            }
        }
    }
}

@Composable
private fun SensitiveAppsContent(
    snapshot: SensitiveAppGuard.SensitiveAppsSnapshot,
    limitReached: Boolean,
    maxProtected: Int,
    onToggle: (String, Boolean) -> Unit,
    onLimitConsumed: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (limitReached) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.sensitive_apps_limit, maxProtected),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.sensitive_apps_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.sensitive_apps_watched)) }
        if (snapshot.protectedApps.isEmpty()) {
            item { Hint(stringResource(R.string.sensitive_apps_no_watched)) }
        } else {
            items(snapshot.protectedApps, key = { "protected-${it.packageName}" }) { app ->
                AppRow(
                    title = app.displayName,
                    subtitle = app.packageName,
                    checked = true,
                    onCheckedChange = { onToggle(app.packageName, it) }
                )
                Divider()
            }
        }

        item { SectionHeader(stringResource(R.string.sensitive_apps_suggestions)) }
        if (snapshot.suggestions.isEmpty()) {
            item { Hint(stringResource(R.string.sensitive_apps_no_suggestions)) }
        } else {
            items(snapshot.suggestions, key = { "suggestion-${it.packageName}" }) { app ->
                AppRow(
                    title = app.displayName,
                    subtitle = app.packageName,
                    checked = false,
                    onCheckedChange = { onToggle(app.packageName, it) }
                )
                Divider()
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.sensitive_apps_exposures),
                subtitle = if (snapshot.exposures.isEmpty()) {
                    null
                } else {
                    stringResource(R.string.sensitive_apps_exposures_hint, snapshot.exposures.size)
                }
            )
        }
        if (snapshot.exposures.isEmpty()) {
            item { Hint(stringResource(R.string.sensitive_apps_no_exposures)) }
        } else {
            items(snapshot.exposures, key = { "exposure-${it.packageName}" }) { exposure ->
                ExposureRow(exposure)
                Divider()
            }
        }

        if (limitReached) {
            item {
                // Снимаем флаг, когда пользователь увидел сообщение и прокрутил дальше:
                // иначе оно висит до перезапуска экрана.
                androidx.compose.runtime.LaunchedEffect(Unit) { onLimitConsumed() }
            }
        }
    }
}

@Composable
private fun ExposureRow(exposure: SensitiveAppPolicy.Exposure) {
    val severityColor = when (exposure.severity) {
        SensitiveAppPolicy.Severity.HIGH -> MaterialTheme.colorScheme.error
        SensitiveAppPolicy.Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
        SensitiveAppPolicy.Severity.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.padding(end = 12.dp)) {
            Text(exposure.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = exposure.capabilities.joinToString(" • ") { capabilityLabel(it) },
                style = MaterialTheme.typography.bodySmall,
                color = severityColor
            )
            Text(
                text = severityLabel(exposure.severity),
                style = MaterialTheme.typography.labelSmall,
                color = severityColor
            )
        }
    }
}

@Composable
private fun AppRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, modifier = Modifier.padding(16.dp))
    }
}

/** Подпись права: пользователю нужно «служба доступности», а не `ACCESSIBILITY`. */
@Composable
private fun capabilityLabel(capability: SensitiveAppPolicy.Capability): String = when (capability) {
    SensitiveAppPolicy.Capability.OVERLAY -> stringResource(R.string.sensitive_apps_cap_overlay)
    SensitiveAppPolicy.Capability.ACCESSIBILITY ->
        stringResource(R.string.sensitive_apps_cap_accessibility)
    SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS ->
        stringResource(R.string.sensitive_apps_cap_notification)
    SensitiveAppPolicy.Capability.DEVICE_ADMIN ->
        stringResource(R.string.sensitive_apps_cap_admin)
}

/** Подпись важности: цвет уже несёт смысл, текст нужен для доступности и ч/б экранов. */
@Composable
private fun severityLabel(severity: SensitiveAppPolicy.Severity): String = when (severity) {
    SensitiveAppPolicy.Severity.HIGH -> stringResource(R.string.sensitive_apps_severity_high)
    SensitiveAppPolicy.Severity.MEDIUM -> stringResource(R.string.sensitive_apps_severity_medium)
    SensitiveAppPolicy.Severity.LOW -> stringResource(R.string.sensitive_apps_severity_low)
}
