package com.phoneguard.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoneguard.R
import com.phoneguard.perm.ConsentPolicy
import com.phoneguard.perm.ProtectionActionPolicy
import com.phoneguard.util.SystemSettingsIntents
import com.phoneguard.util.findActivity

/**
 * Экран согласия: разрешения и то, что с ними стало.
 *
 * Раньше галочки были декоративными (системный запрос не вызывался вовсе), затем — с запросом,
 * но без состояния: после «больше не спрашивать» нажатие на галочку молча ничего не делало,
 * потому что Android второй диалог не показывает. Кнопка «Принять» требовала **все** разрешения,
 * поэтому человек с постоянным отказом не мог пройти онбординг вовсе — оставалось «Отклонить».
 *
 * Теперь состояние каждого пункта считается из системы и памяти (`ConsentPolicy`), действие берётся
 * из общей `ProtectionActionPolicy`, а закончить экран можно, как только по каждому пункту дан
 * ответ — выдан или отклонён.
 */
@Composable
fun ConsentScreen(
    viewModel: ConsentViewModel = hiltViewModel(),
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val askedPermissions by viewModel.askedPermissions.collectAsState()

    // Отказ галочкой: системный диалог не показывался, но ответ пользователь дал.
    var declinedLocally by remember { mutableStateOf(emptySet<String>()) }
    // Разрешение могли выдать в системных настройках, пока экран был в фоне — пересчитываем
    // состояние при каждом возвращении, иначе галочка осталась бы снятой.
    var refresh by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Результат не запоминаем отдельно: состояние пересчитывается из системы.
        refresh++
    }

    val entries = remember {
        listOf(
            ConsentEntry(Manifest.permission.CAMERA, R.string.consent_camera, Icons.Default.PhotoCamera),
            ConsentEntry(Manifest.permission.SEND_SMS, R.string.consent_sms, Icons.Default.Sms),
            ConsentEntry(Manifest.permission.READ_PHONE_STATE, R.string.consent_phone, Icons.Default.Phone),
            ConsentEntry(
                Manifest.permission.ACCESS_FINE_LOCATION,
                R.string.consent_location,
                Icons.Default.LocationOn
            ),
            ConsentEntry(
                Manifest.permission.POST_NOTIFICATIONS,
                R.string.consent_notifications,
                Icons.Default.Notifications
            )
        )
    }

    val states = key(refresh, askedPermissions, declinedLocally) {
        entries.map { entry ->
            consentState(
                permission = entry.permission,
                context = context,
                asked = askedPermissions,
                declinedLocally = declinedLocally
            )
        }
    }
    val canContinue = ConsentPolicy.canContinue(states)
    val pending = ConsentPolicy.pendingCount(states)
    val disabled = ConsentPolicy.disabledCount(states)

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

            entries.forEachIndexed { index, entry ->
                val state = states[index]
                ConsentCard(
                    entry = entry,
                    state = state,
                    onToggle = { wanted ->
                        if (!wanted) {
                            declinedLocally = declinedLocally + entry.permission
                        } else {
                            when (val action = ConsentPolicy.action(state)) {
                                null -> Unit
                                ProtectionActionPolicy.Action.REQUEST_PERMISSION -> {
                                    // Отметка ставится до запуска: после отказа Android уже
                                    // не отличит «спрашивали» от «не спрашивали».
                                    viewModel.rememberAsked(entry.permission)
                                    permissionLauncher.launch(entry.permission)
                                }
                                else -> SystemSettingsIntents.open(context, action, context.packageName)
                            }
                        }
                    }
                )
            }

            if (pending > 0) {
                Text(
                    text = stringResource(R.string.consent_pending_hint, pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (disabled > 0) {
                Text(
                    text = stringResource(R.string.consent_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onAccept,
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.consent_accept))
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

/** Состояние пункта: выдано ли, спрашивали ли и покажет ли система диалог ещё раз. */
private fun consentState(
    permission: String,
    context: Context,
    asked: Set<String>,
    declinedLocally: Set<String>
): ConsentPolicy.State {
    val granted = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
    val canAskAgain = context.findActivity()?.let { activity ->
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    } ?: false
    return ConsentPolicy.state(
        granted = granted,
        askedBefore = permission in asked || permission in declinedLocally,
        canAskAgain = canAskAgain
    )
}

@Composable
private fun ConsentCard(
    entry: ConsentEntry,
    state: ConsentPolicy.State,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(imageVector = entry.icon, contentDescription = null)
                Text(
                    text = stringResource(entry.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Checkbox(
                    checked = state == ConsentPolicy.State.GRANTED,
                    // Выданное разрешение здесь не отзывается: снятая галочка при выданом
                    // разрешении — это ложь, которая и была до исправления.
                    enabled = state != ConsentPolicy.State.GRANTED,
                    onCheckedChange = { onToggle(it) }
                )
            }
            if (state == ConsentPolicy.State.DECLINED_CAN_RETRY) {
                Text(
                    text = stringResource(R.string.consent_declined_can_retry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state == ConsentPolicy.State.DECLINED_FOREVER) {
                Text(
                    text = stringResource(R.string.consent_declined_forever),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(
                    onClick = {
                        SystemSettingsIntents.open(
                            context,
                            ProtectionActionPolicy.Action.OPEN_APP_DETAILS,
                            context.packageName
                        )
                    }
                ) {
                    Text(stringResource(R.string.open_app_settings))
                }
            }
        }
    }
}

data class ConsentEntry(
    val permission: String,
    val titleRes: Int,
    val icon: ImageVector
)
