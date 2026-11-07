package com.phoneguard.spywarecheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phoneguard.R
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.malware.ApkSignatureScanner
import com.phoneguard.protect.NeighbourAlertPolicy
import com.phoneguard.protect.SensitiveAppGuard
import com.phoneguard.protect.SensitiveAppPolicy
import com.phoneguard.ui.MainActivity
import com.phoneguard.util.InstalledAppsCache
import com.phoneguard.util.NotificationChannelInstaller
import com.phoneguard.util.NotificationChannels
import com.phoneguard.util.NotificationIds
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Реагирует на установку стороннего APK двумя независимыми поводами.
 *
 * 1. **Подпись.** Раньше уведомление было безусловным («Установлено новое приложение»), из-за
 *    чего пользователь переставал их читать. Теперь подпись APK разбирается локально через
 *    [ApkSignatureScanner], и уведомление показывается только при реальной проблеме:
 *    debug-ключ, слабый алгоритм (MD5/SHA-1) или истёкший сертификат.
 * 2. **Опасный сосед.** Если у пользователя есть приложения под защитой, а новое умеет рисовать
 *    поверх окон или читать уведомления — это то самое окно, когда решение ещё можно принять.
 *    Правила (кого не считать угрозой, насколько часто повторять) — в
 *    [NeighbourAlertPolicy]; здесь только Android-часть.
 *
 * Оба уведомления идут в разных каналах и с разными id-диапазонами
 * ([NotificationIds.packageAdded] и [NotificationIds.neighbour]): у одного пакета это два разных
 * события, и одно не должно затирать другое.
 */
@AndroidEntryPoint
class PackageAddedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var apkSignatureScanner: ApkSignatureScanner

    @Inject
    lateinit var sensitiveAppGuard: SensitiveAppGuard

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var installedAppsCache: InstalledAppsCache

    override fun onReceive(context: Context, intent: Intent) {
        // Кэш списка приложений живёт 24 часа и используется экраном фаервола. Без сброса
        // установленное приложение не появлялось в списке до суток (а удалённое оставалось),
        // и правило для него создать было нельзя. Сброс делается до всех ранних выходов:
        // список изменился независимо от того, интересен ли нам этот пакет.
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
            installedAppsCache.invalidateCache()
            return
        }
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        installedAppsCache.invalidateCache()
        val packageName = intent.data?.schemeSpecificPart ?: return
        Log.d(TAG, "New package installed: $packageName")

        val appInfo = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: Exception) {
            null
        } ?: return

        // Системные приложения и обновления самого PhoneGuard не интересны.
        if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return
        if (packageName == context.packageName) return

        val appName = runCatching {
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                notifySuspiciousSignature(context, packageName, appName)
                notifyDangerousNeighbour(context, packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Package inspection failed for $packageName", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Подпись APK: debug-ключ, слабый алгоритм или истёкший сертификат. */
    private suspend fun notifySuspiciousSignature(
        context: Context,
        packageName: String,
        appName: String
    ) {
        val signature = runCatching {
            apkSignatureScanner.inspectInstalledPackage(packageName)
        }.getOrNull() ?: return

        if (!signature.isInstalledFromDebugKey &&
            !signature.hasWeakSignature &&
            !signature.isCertificateExpired
        ) {
            return
        }

        notify(
            context = context,
            notificationId = NotificationIds.packageAdded(packageName),
            channel = NotificationChannels.Spec.NEW_APP,
            channelName = context.getString(R.string.new_app_channel_name),
            title = context.getString(R.string.new_app_suspicious_title),
            text = context.getString(R.string.new_app_suspicious_text, appName)
        )
    }

    /**
     * «Опасный сосед»: новое приложение с правами рядом с защищёнными.
     *
     * Тап ведёт на экран «Защита приложений» (`phoneguard://protect`): уведомление, после
     * которого нечего нажать, — это просто тревога.
     */
    private suspend fun notifyDangerousNeighbour(context: Context, packageName: String) {
        val facts = runCatching { sensitiveAppGuard.factsFor(packageName) }.getOrNull() ?: return
        val protectedApps = preferencesManager.protectedApps.first()
        val now = System.currentTimeMillis()

        val alert = NeighbourAlertPolicy.decide(
            facts = facts,
            protected = protectedApps,
            ownPackageName = context.packageName,
            lastAlertAt = preferencesManager.neighbourAlerts(now)[packageName],
            nowMillis = now
        ) ?: return

        val capabilities = alert.capabilities.joinToString(", ") { capability ->
            context.getString(capabilityLabel(capability))
        }

        notify(
            context = context,
            notificationId = NotificationIds.neighbour(packageName),
            channel = NotificationChannels.Spec.NEIGHBOUR,
            channelName = context.getString(R.string.neighbour_channel_name),
            title = context.getString(R.string.neighbour_alert_title),
            text = context.getString(
                R.string.neighbour_alert_text,
                alert.displayName,
                alert.protectedCount,
                capabilities
            ),
            openProtectScreen = true
        )
        // Отметка ставится после показа: если уведомление не прошло (нет разрешения),
        // следующий повод должен снова попробовать.
        preferencesManager.recordNeighbourAlert(packageName, now)
    }

    /** Право → подпись для пользователя. */
    private fun capabilityLabel(capability: SensitiveAppPolicy.Capability): Int = when (capability) {
        SensitiveAppPolicy.Capability.OVERLAY -> R.string.neighbour_capability_overlay
        SensitiveAppPolicy.Capability.ACCESSIBILITY -> R.string.neighbour_capability_accessibility
        SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS ->
            R.string.neighbour_capability_notification_access
        SensitiveAppPolicy.Capability.DEVICE_ADMIN -> R.string.neighbour_capability_device_admin
    }

    private fun notify(
        context: Context,
        notificationId: Int,
        channel: NotificationChannels.Spec,
        channelName: String,
        title: String,
        text: String,
        openProtectScreen: Boolean = false
    ) {
        createNotificationChannel(context, channel, channelName)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val builder = NotificationCompat.Builder(context, channel.channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // Не adaptive-иконка: её foreground в статус-баре выглядит пустым пятном.
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (openProtectScreen) {
            val target = Intent(Intent.ACTION_VIEW, Uri.parse(PROTECT_URI), context, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    target,
                    // FLAG_IMMUTABLE обязателен с Android 12: без него система бросает
                    // IllegalArgumentException на PendingIntent без явных мутаций.
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        try {
            manager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // На Android 13+ без POST_NOTIFICATIONS уведомление просто не показываем.
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    private fun createNotificationChannel(
        context: Context,
        channel: NotificationChannels.Spec,
        channelName: String
    ) {
        NotificationChannelInstaller.install(context, channel, channelName)
    }

    companion object {
        private const val TAG = "PackageAddedReceiver"

        /** Куда ведёт тап по уведомлению об опасном соседе. */
        private const val PROTECT_URI = "phoneguard://protect"

        /**
         * Уведомление на каждый пакет, а не одно общее.
         *
         * Диапазон живёт в [NotificationIds]: прежний `2000..6095` накрывал `4101` —
         * подтверждение подмены SIM, и установка приложения могла стереть предупреждение о
         * подмене.
         */
        fun notificationIdFor(packageName: String): Int =
            NotificationIds.packageAdded(packageName)
    }
}
