package com.phoneguard.ui.screens.antitheft

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.antitheft.DeviceAdminReceiverImpl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiTheftScreen(
    viewModel: AntiTheftViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.checkDeviceAdminStatus()
    }

    var showPinDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.anti_theft_setup)) })
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Admin Card
            FeatureToggleCard(
                title = stringResource(R.string.device_admin),
                description = stringResource(R.string.device_admin_description),
                checked = uiState.isDeviceAdminEnabled,
                icon = Icons.Default.Security,
                onCheckedChange = {
                    if (it) {
                        val componentName = DeviceAdminReceiverImpl.getComponentName(context)
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_description))
                        }
                        deviceAdminLauncher.launch(intent)
                    }
                }
            )

            // PIN Card
            PinSetupCard(
                hasPin = uiState.hasPin,
                onSetPin = { showPinDialog = true }
            )

            // Backup Number Card
            BackupNumberCard(
                backupNumber = uiState.backupNumber,
                onSave = { viewModel.setBackupNumber(it) },
                isValid = { viewModel.validatePhoneNumber(it) }
            )

            Divider()

            // Protection Toggles
            ProtectionTogglesSection(
                uiState = uiState,
                viewModel = viewModel
            )

            Divider()

            // How it Works
            HowItWorksSection()

            if (showPinDialog) {
                PinSetupDialog(
                    onDismiss = { showPinDialog = false },
                    onConfirm = { pin ->
                        viewModel.setPin(pin)
                        showPinDialog = false
                    },
                    validatePin = { viewModel.validatePin(it) }
                )
            }
        }
    }
}

@Composable
fun FeatureToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(imageVector = icon, contentDescription = title)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun PinSetupCard(
    hasPin: Boolean,
    onSetPin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSetPin
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.pin_code),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (hasPin) stringResource(R.string.pin_set) else stringResource(R.string.pin_not_set),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun BackupNumberCard(
    backupNumber: String,
    onSave: (String) -> Unit,
    isValid: (String) -> Boolean
) {
    var input by remember { mutableStateOf(backupNumber) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Default.Phone, contentDescription = null)
                Text(
                    text = stringResource(R.string.backup_number),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { 
                    input = it 
                },
                label = { Text(stringResource(R.string.enter_backup_number)) },
                placeholder = { Text(stringResource(R.string.backup_number_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = input.isNotEmpty() && !isValid(input)
            )
            if (input.isNotEmpty() && isValid(input)) {
                Button(
                    onClick = { onSave(input) },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
fun ProtectionTogglesSection(
    uiState: AntiTheftUiState,
    viewModel: AntiTheftViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.protection_features),
            style = MaterialTheme.typography.titleMedium
        )

        FeatureToggleCard(
            title = stringResource(R.string.sim_lock),
            description = stringResource(R.string.sim_lock_enabled),
            checked = uiState.isSimLockEnabled,
            icon = Icons.Default.SimCard,
            onCheckedChange = { viewModel.toggleSimLock(it) }
        )

        FeatureToggleCard(
            title = stringResource(R.string.failed_attempts_photo),
            description = stringResource(R.string.photo_on_failed_attempts_enabled),
            checked = uiState.isPhotoEnabled,
            icon = Icons.Default.PhotoCamera,
            onCheckedChange = { viewModel.togglePhotoOnFailedAttempts(it) }
        )

        FeatureToggleCard(
            title = stringResource(R.string.remote_alarm),
            description = stringResource(R.string.remote_alarm_description),
            checked = uiState.isRemoteAlarmEnabled,
            icon = Icons.Default.NotificationsActive,
            onCheckedChange = { viewModel.toggleRemoteAlarm(it) }
        )
    }
}

@Composable
fun HowItWorksSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.how_it_works),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.sms_commands_description),
            style = MaterialTheme.typography.bodyMedium
        )
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandItem(command = stringResource(R.string.command_lock), desc = stringResource(R.string.command_lock_desc))
                CommandItem(command = stringResource(R.string.command_alarm), desc = stringResource(R.string.command_alarm_desc))
                CommandItem(command = stringResource(R.string.command_location), desc = stringResource(R.string.command_location_desc))
                CommandItem(command = stringResource(R.string.command_wipe), desc = stringResource(R.string.command_wipe_desc))
            }
        }
    }
}

@Composable
fun CommandItem(command: String, desc: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = command, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(text = desc, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PinSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    validatePin: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.enter_pin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = pin.isNotEmpty() && !validatePin(pin)
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it },
                    label = { Text(stringResource(R.string.confirm_pin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = confirmPin.isNotEmpty() && confirmPin != pin
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin) },
                enabled = validatePin(pin) && pin == confirmPin
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
