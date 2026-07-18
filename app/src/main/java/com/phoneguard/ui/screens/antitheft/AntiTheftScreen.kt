package com.phoneguard.ui.screens.antitheft

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.antitheft.DeviceAdminReceiverImpl
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiTheftScreen(viewModel: AntiTheftViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var backupNumberInput by remember { mutableStateOf(uiState.backupNumber) }

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest { state ->
            backupNumberInput = state.backupNumber
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.anti_theft)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // PIN Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null)
                        Text(text = stringResource(R.string.pin_code), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (!uiState.hasPin) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it.take(6) },
                            label = { Text(stringResource(R.string.set_pin)) },
                            visualTransformation = PasswordVisualTransformation(),
                            isError = pinInput.isNotEmpty() && !viewModel.validatePin(pinInput),
                            supportingText = {
                                if (pinInput.isNotEmpty() && !viewModel.validatePin(pinInput)) {
                                    Text(stringResource(R.string.pin_too_short))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (viewModel.validatePin(pinInput)) {
                                    viewModel.setPin(pinInput)
                                    pinInput = ""
                                }
                            },
                            enabled = viewModel.validatePin(pinInput),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    } else {
                        Text(text = stringResource(R.string.pin_set), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Backup Number Section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Phone, contentDescription = null)
                        Text(text = stringResource(R.string.backup_number), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(
                        value = backupNumberInput,
                        onValueChange = { backupNumberInput = it },
                        label = { Text(stringResource(R.string.backup_number_hint)) },
                        isError = backupNumberInput.isNotEmpty() && !viewModel.validatePhoneNumber(backupNumberInput),
                        supportingText = {
                            if (backupNumberInput.isNotEmpty() && !viewModel.validatePhoneNumber(backupNumberInput)) {
                                Text(stringResource(R.string.error_invalid_number))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.setBackupNumber(backupNumberInput) },
                        enabled = backupNumberInput.isBlank() || viewModel.validatePhoneNumber(backupNumberInput),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }

            // Device Admin
            FeatureToggleCard(
                title = stringResource(R.string.device_admin),
                description = stringResource(R.string.device_admin_description),
                icon = Icons.Default.Security,
                checked = uiState.isDeviceAdminEnabled,
                onCheckedChange = { /* handled by button */ },
                buttonText = if (uiState.isDeviceAdminEnabled) stringResource(R.string.disable) else stringResource(R.string.enable_device_admin),
                onButtonClick = {
                    val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val componentName = DeviceAdminReceiverImpl.getComponentName(context)
                    if (uiState.isDeviceAdminEnabled) {
                        devicePolicyManager.removeActiveAdmin(componentName)
                        viewModel.checkDeviceAdminStatus()
                    } else {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_description))
                        }
                        context.startActivity(intent)
                    }
                }
            )

            // SIM Lock
            FeatureToggleCard(
                title = stringResource(R.string.sim_lock),
                description = stringResource(R.string.sim_lock_enabled),
                icon = Icons.Default.Phone,
                checked = uiState.isSimLockEnabled,
                onCheckedChange = { viewModel.toggleSimLock(it) }
            )

            // Photo on failed attempts
            FeatureToggleCard(
                title = stringResource(R.string.failed_attempts_photo),
                description = stringResource(R.string.photo_on_failed_attempts_enabled),
                icon = Icons.Default.PhotoCamera,
                checked = uiState.isPhotoEnabled,
                onCheckedChange = { viewModel.togglePhotoOnFailedAttempts(it) }
            )

            // Remote Alarm
            FeatureToggleCard(
                title = stringResource(R.string.remote_alarm),
                description = stringResource(R.string.remote_alarm_description),
                icon = Icons.Default.Vibration,
                checked = uiState.isRemoteAlarmEnabled,
                onCheckedChange = { viewModel.toggleRemoteAlarm(it) }
            )
        }
    }
}

@Composable
private fun FeatureToggleCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(imageVector = icon, contentDescription = null)
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (buttonText != null && onButtonClick != null) {
                    Button(onClick = onButtonClick) {
                        Text(buttonText)
                    }
                } else {
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )
                }
            }
        }
    }
}
