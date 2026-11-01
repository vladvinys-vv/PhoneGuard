package com.phoneguard.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneguard.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.phoneguard.util.IntentUtils

@Composable
fun ConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val consents = remember {
        mutableStateListOf(
            ConsentItem(Manifest.permission.CAMERA, R.string.consent_camera, Icons.Default.PhotoCamera),
            ConsentItem(Manifest.permission.SEND_SMS, R.string.consent_sms, Icons.Default.Sms),
            ConsentItem(Manifest.permission.READ_PHONE_STATE, R.string.consent_phone, Icons.Default.Phone),
            ConsentItem(Manifest.permission.ACCESS_FINE_LOCATION, R.string.consent_location, Icons.Default.LocationOn),
            ConsentItem(Manifest.permission.POST_NOTIFICATIONS, R.string.consent_notifications, Icons.Default.Notifications)
        ).map { it.copy(accepted = it.isGranted(context)) }.toMutableStateList()
    }

    // Раньше галочки на этом экране были декоративными: системный запрос
    // разрешений не вызывался вовсе, и функции (SMS-команды, геолокация,
    // уведомления, фото при неудачном PIN) молча не работали.
    var pendingIndex by remember { mutableStateOf(-1) }
    // Если пользователь отказал «навсегда», запрос больше не покажется —
    // без ссылки в настройки он застревал на этом экране.
    var deniedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val index = pendingIndex
        if (index >= 0 && index < consents.size) {
            consents[index] = consents[index].copy(accepted = granted)
        }
        if (!granted) deniedOnce = true
        pendingIndex = -1
    }

    val allAccepted = consents.all { it.accepted }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.consent_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.consent_description),
                style = MaterialTheme.typography.bodyMedium
            )

            // forEachIndexed вместо forEach + indexOf: равенство ConsentItem не
            // учитывает индекс, и при двух одинаковых строках (например, после
            // копирования) indexOf мог вернуть чужую позицию и переключить не тот
            // переключатель. Заодно исчез квадратичный поиск на каждом клике.
            consents.forEachIndexed { index, consent ->
                ConsentCard(
                    item = consent,
                    onToggle = { accepted ->
                        when {
                            !accepted -> consents[index] = consent.copy(accepted = false)
                            consent.isGranted(context) ->
                                consents[index] = consent.copy(accepted = true)
                            else -> {
                                pendingIndex = index
                                permissionLauncher.launch(consent.permission)
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onAccept,
                enabled = allAccepted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.consent_accept))
            }

            if (deniedOnce && !allAccepted) {
                TextButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings))
                }
            }

            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.consent_decline))
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    IntentUtils.open(context, intent)
}

@Composable
private fun ConsentCard(
    item: ConsentItem,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = item.icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Checkbox(
                checked = item.accepted,
                onCheckedChange = onToggle
            )
        }
    }
}

data class ConsentItem(
    val permission: String,
    val titleRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accepted: Boolean = false
) {
    /** Уже выдано системой? На Android < 33 `POST_NOTIFICATIONS` считается выданным. */
    fun isGranted(context: android.content.Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}