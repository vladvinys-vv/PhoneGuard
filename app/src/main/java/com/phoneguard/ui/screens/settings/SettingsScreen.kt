package com.phoneguard.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Brightness3
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Battery3
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.data.preferences.PreferencesManager
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        TopAppBar(title = { Text(stringResource(R.string.settings)) })

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Language, contentDescription = stringResource(R.string.language))
                        Text(text = stringResource(R.string.language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    LanguageSelector(settingsViewModel = settingsViewModel)
                }
            }

            // Dark Theme Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Brightness3, contentDescription = stringResource(R.string.dark_theme))
                        Column {
                            Text(text = stringResource(R.string.dark_theme), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(text = stringResource(R.string.dark_theme_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val isDarkTheme by settingsViewModel.isDarkTheme.collectAsState()
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = { checked ->
                            settingsViewModel.setDarkTheme(checked)
                            (context as? android.app.Activity)?.recreate()
                        }
                    )
                }
            }

            // Scheduled Scan Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = stringResource(R.string.scheduled_scan))
                        Column {
                            Text(text = stringResource(R.string.scheduled_scan), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(text = stringResource(R.string.schedule_scan_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val isScheduledScanEnabled by settingsViewModel.isScheduledScanEnabled.collectAsState()
                    Switch(
                        checked = isScheduledScanEnabled,
                        onCheckedChange = { checked ->
                            settingsViewModel.setScheduledScan(checked)
                        }
                    )
                }
            }

            // Privacy Policy
            SettingsLinkCard(
                title = stringResource(R.string.privacy_policy),
                description = "Read our privacy policy",
                icon = Icons.Default.Policy,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://phoneguard.app/privacy"))
                    context.startActivity(intent)
                }
            )

            // Terms of Service
            SettingsLinkCard(
                title = stringResource(R.string.terms_of_service),
                description = "Read our terms of service",
                icon = Icons.Default.Info,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://phoneguard.app/terms"))
                    context.startActivity(intent)
                }
            )

            // About
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.about), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = "PhoneGuard v1.0.0", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Your personal security assistant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Export Logs
            val exportResult by settingsViewModel.exportResult.collectAsState()
            exportResult?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            Button(
                onClick = {
                    settingsViewModel.exportLogs()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.export_logs))
            }

            // GDPR Delete All Data
            var showDeleteDialog by remember { mutableStateOf(false) }
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(stringResource(R.string.delete_all_data)) },
                    text = { Text(stringResource(R.string.delete_all_data_description)) },
                    confirmButton = {
                        TextButton(onClick = {
                            settingsViewModel.deleteAllData()
                            showDeleteDialog = false
                        }) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.delete_all_data))
            }

            // Battery Optimization
            OutlinedButton(
                onClick = {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Battery3, contentDescription = stringResource(R.string.battery_optimization_title))
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.open_battery_settings))
            }
        }
    }
}

@Composable
private fun SettingsLinkCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = title)
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LanguageSelector(settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val languages = listOf("Русский" to "ru", "English" to "en")
    var selectedLanguage by remember { mutableStateOf("Русский") }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        TextField(
            value = selectedLanguage,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { (name, code) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        selectedLanguage = name
                        expanded = false
                        settingsViewModel.setLanguage(code)
                        setLocale(code, context)
                    }
                )
            }
        }
    }
}

private fun setLocale(language: String, context: Context) {
    val locale = Locale(language)
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}
