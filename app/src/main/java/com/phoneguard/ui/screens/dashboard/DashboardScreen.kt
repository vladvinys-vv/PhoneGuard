package com.phoneguard.ui.screens.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoneguard.R
import com.phoneguard.perm.ProtectionActionPolicy
import com.phoneguard.perm.ProtectionCapabilityPolicy
import com.phoneguard.util.SystemSettingsIntents
import com.phoneguard.util.findActivity
import com.phoneguard.ui.screens.dashboard.DashboardViewModel.FullScanSummary
import com.phoneguard.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToAntiTheft: () -> Unit,
    onNavigateToCallBlocker: () -> Unit,
    onNavigateToPrivacyScanner: () -> Unit,
    onNavigateToSpywareCheck: () -> Unit,
    onNavigateToFullScan: () -> Unit,
    onNavigateToSimSwap: () -> Unit,
    onNavigateToPhoneCheck: () -> Unit,
    onNavigateToSensitiveApps: () -> Unit
) {
    val score by viewModel.securityScore.collectAsState()
    val fullScanSummary by viewModel.fullScanSummary.collectAsState()
    val phoneCheckSummary by viewModel.phoneCheckSummary.collectAsState()
    val degradedCapabilities by viewModel.degradedCapabilities.collectAsState()
    val askedPermissions by viewModel.askedPermissions.collectAsState()
    val context = LocalContext.current

    // Вызывается всегда, а не только при наличии проблем: у Compose порядок вызовов
    // фиксирован, и launcher внутри `if` ломал композицию при первом же исправлении.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.recalculateScore() }

    // Сводка последнего сканирования читалась только в init: после возврата с
    // экрана сканирования дашборд продолжал показывать старую дату и оценку.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.recalculateScore()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Дашборд прокручивается: карточек стало больше, и на невысоких экранах нижние
    // («Смена SIM», «Защита приложений») просто обрезались — до них нельзя было дойти.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.dashboard),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        if (degradedCapabilities.isNotEmpty()) {
            DegradedProtectionCard(
                rows = degradedCapabilities.map { capability ->
                    DegradedProtectionRow(
                        capability = capability,
                        action = resolveAction(
                            capability = capability,
                            context = context,
                            askedBefore = capability.requiredPermission() in askedPermissions
                        )
                    )
                },
                onAction = { capability ->
                    val permission = capability.requiredPermission()
                        ?: return@DegradedProtectionCard
                    when (
                        val action = resolveAction(
                            capability = capability,
                            context = context,
                            askedBefore = permission in askedPermissions
                        )
                    ) {
                        null -> viewModel.recalculateScore()
                        ProtectionActionPolicy.Action.REQUEST_PERMISSION -> {
                            viewModel.rememberPermissionAsked(permission)
                            permissionLauncher.launch(permission)
                        }
                        else -> SystemSettingsIntents.open(context, action, context.packageName)
                    }
                }
            )
        }

        SecurityScoreCard(score = score.total)

        FullScanCard(
            summary = fullScanSummary,
            onClick = onNavigateToFullScan
        )

        PhoneCheckCard(
            summary = phoneCheckSummary,
            onClick = onNavigateToPhoneCheck
        )

        SecurityFeatureCards(
            score = score,
            onAntiTheftClick = onNavigateToAntiTheft,
            onCallBlockerClick = onNavigateToCallBlocker,
            onPrivacyClick = onNavigateToPrivacyScanner,
            onSpywareClick = onNavigateToSpywareCheck
        )

        SimSwapStatusCard(onClick = onNavigateToSimSwap)

        SensitiveAppsCard(onClick = onNavigateToSensitiveApps)
    }
}

/**
 * Вход в «Защиту приложений».
 *
 * Карточка без цифр намеренно: сколько приложений отмечено и сколько рядом умеет
 * подглядывать, зависит от установленных приложений и прав, а считать это на дашборде
 * значило бы опрашивать `PackageManager` и `AppOps` при каждом возврате на экран.
 */
@Composable
fun SensitiveAppsCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sensitive_apps_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.sensitive_apps_card_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Строка карточки: что ослаблено и что произойдёт по нажатию (`null` — предложить нечего). */
data class DegradedProtectionRow(
    val capability: ProtectionCapabilityPolicy.Capability,
    val action: ProtectionActionPolicy.Action?
)

/**
 * Предупреждение «защита работает не полностью».
 *
 * Разрешения запрашивались один раз на онбординге. Если пользователь отозвал CAMERA, SEND_SMS,
 * READ_PHONE_STATE или POST_NOTIFICATIONS, соответствующая функция просто перестаёт работать —
 * и раньше об этом не сообщал никто. Список и тексты берутся из [ProtectionCapabilityPolicy],
 * тексты — из уже существующих объяснений разрешений.
 */
@Composable
fun DegradedProtectionCard(
    rows: List<DegradedProtectionRow>,
    onAction: (ProtectionCapabilityPolicy.Capability) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.protection_degraded_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(row.capability.rationaleRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    val action = row.action
                    if (action != null) {
                        TextButton(onClick = { onAction(row.capability) }) {
                            Text(
                                text = stringResource(
                                    if (action == ProtectionActionPolicy.Action.REQUEST_PERMISSION) {
                                        R.string.protection_action_grant
                                    } else {
                                        R.string.protection_action_open_settings
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Имя разрешения для запроса — единственное место, где enum превращается в строку Android.
 *
 * Таблица «возможность → разрешение» живёт в [ProtectionCapabilityPolicy]; здесь только
 * соответствие enum и константы `Manifest.permission`.
 */
private fun ProtectionCapabilityPolicy.CapabilityPermission.permissionName(): String = when (this) {
    ProtectionCapabilityPolicy.CapabilityPermission.PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
    ProtectionCapabilityPolicy.CapabilityPermission.CAMERA -> Manifest.permission.CAMERA
    ProtectionCapabilityPolicy.CapabilityPermission.SEND_SMS -> Manifest.permission.SEND_SMS
    ProtectionCapabilityPolicy.CapabilityPermission.FINE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
    ProtectionCapabilityPolicy.CapabilityPermission.POST_NOTIFICATIONS ->
        Manifest.permission.POST_NOTIFICATIONS
}

/**
 * Какое разрешение относится к возможности (по таблице политики, без своей копии).
 *
 * `null` — в таблице для этой версии Android разрешения нет (POST_NOTIFICATIONS до API 33),
 * значит и предлагать нечего: уведомления там выдаются при установке.
 */
private fun ProtectionCapabilityPolicy.Capability.requiredPermission(): String? =
    ProtectionCapabilityPolicy.permissionOf(this, android.os.Build.VERSION.SDK_INT)?.permissionName()

/**
 * Что предложить для ослабленной возможности прямо сейчас.
 *
 * Считается на месте и без Android-состояния в политике: решение зависит от того, выдано ли
 * разрешение, покажет ли система диалог ещё раз и включён ли показ уведомлений.
 */
private fun resolveAction(
    capability: ProtectionCapabilityPolicy.Capability,
    context: android.content.Context,
    askedBefore: Boolean
): ProtectionActionPolicy.Action? {
    val permission = capability.requiredPermission() ?: return null
    val granted = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
    val canAskAgain = context.findActivity()?.let { activity ->
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    } ?: false
    return if (capability == ProtectionCapabilityPolicy.Capability.INTRUSION_WARNING) {
        ProtectionActionPolicy.notificationAction(
            permissionGranted = granted,
            appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            askedBefore = askedBefore,
            canAskAgain = canAskAgain
        )
    } else {
        ProtectionActionPolicy.permissionAction(
            granted = granted,
            askedBefore = askedBefore,
            canAskAgain = canAskAgain
        )
    }
}

/** Какое объяснение показать для ослабленной возможности (строки уже есть в ресурсах). */
private fun ProtectionCapabilityPolicy.Capability.rationaleRes(): Int = when (this) {
    ProtectionCapabilityPolicy.Capability.SIM_SWAP_DETECTION -> R.string.phone_state_permission_rationale
    ProtectionCapabilityPolicy.Capability.INTRUSION_PHOTO -> R.string.camera_permission_rationale
    ProtectionCapabilityPolicy.Capability.INTRUSION_SMS -> R.string.sms_permission_rationale
    ProtectionCapabilityPolicy.Capability.TRUSTED_CONTACT_LOCATION -> R.string.location_permission_rationale
    ProtectionCapabilityPolicy.Capability.INTRUSION_WARNING -> R.string.notifications_permission_rationale
}

@Composable
fun SimSwapStatusCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sim_swap_status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sim_swap_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.sim_swap),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun FullScanCard(summary: FullScanSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.full_scan),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary.dateText ?: stringResource(R.string.scan_not_performed_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (summary.riskScore != null) {
                Text(
                    text = "${summary.riskScore}/100",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        summary.riskScore >= 80 -> ScoreExcellent
                        summary.riskScore >= 50 -> ScoreGood
                        else -> ScoreCritical
                    }
                )
            }
        }
    }
}

@Composable
fun SecurityScoreCard(score: Int) {
    val (color, label) = when {
        score >= 80 -> ScoreExcellent to stringResource(R.string.excellent)
        score >= 60 -> ScoreGood to stringResource(R.string.good)
        score >= 40 -> ScoreFair to stringResource(R.string.fair)
        score >= 20 -> ScorePoor to stringResource(R.string.poor)
        else -> ScoreCritical to stringResource(R.string.critical)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.security_score),
                style = MaterialTheme.typography.titleMedium,
                color = color
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(150.dp)
            ) {
                CircularProgressIndicator(
                    progress = { scoreFraction(score) },
                    modifier = Modifier.size(150.dp),
                    color = color,
                    strokeWidth = 12.dp,
                    trackColor = color.copy(alpha = 0.2f)
                )
                Text(
                    text = "$score",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SecurityFeatureCards(
    score: SecurityScore,
    onAntiTheftClick: () -> Unit,
    onCallBlockerClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onSpywareClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureCard(
            title = stringResource(R.string.anti_theft),
            score = score.antiTheft,
            icon = androidx.compose.material.icons.Icons.Default.Lock,
            onClick = onAntiTheftClick
        )
        
        FeatureCard(
            title = stringResource(R.string.call_sms_blocker),
            score = score.callBlocker,
            icon = androidx.compose.material.icons.Icons.Default.Block,
            onClick = onCallBlockerClick
        )
        
        FeatureCard(
            title = stringResource(R.string.privacy_scanner),
            score = score.privacy,
            icon = androidx.compose.material.icons.Icons.Default.VisibilityOff,
            onClick = onPrivacyClick
        )
        
        FeatureCard(
            title = stringResource(R.string.spyware_check),
            score = score.spyware,
            icon = androidx.compose.material.icons.Icons.Default.Search,
            onClick = onSpywareClick
        )
    }
}

@Composable
fun FeatureCard(
    title: String,
    score: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Box(
                modifier = Modifier.size(50.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { scoreFraction(score) },
                    modifier = Modifier.size(50.dp),
                    color = getScoreColor(score),
                    strokeWidth = 6.dp,
                    trackColor = getScoreColor(score).copy(alpha = 0.2f)
                )
                Text(
                    text = "$score",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = getScoreColor(score)
                )
            }
        }
    }
}

/**
 * Доля заполнения круга 0..1.
 *
 * Без `coerceIn` оценка вне 0..100 (например, из старой записи в истории после
 * смены формулы) отрисовывала дугу «на 130%» — визуально кольцо залезало само на себя.
 */
private fun scoreFraction(score: Int): Float = (score / 100f).coerceIn(0f, 1f)

private fun getScoreColor(score: Int): Color {
    return when {
        score >= 80 -> ScoreExcellent
        score >= 60 -> ScoreGood
        score >= 40 -> ScoreFair
        score >= 20 -> ScorePoor
        else -> ScoreCritical
    }
}

/** Карточка «чек всего телефона»: состояние устройства и число проблем. */
@Composable
fun PhoneCheckCard(summary: DeviceAuditSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.phone_check_run),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary.score?.let {
                        stringResource(R.string.phone_check_score_short, it, summary.issues)
                    } ?: stringResource(R.string.phone_check_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.phone_check),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
