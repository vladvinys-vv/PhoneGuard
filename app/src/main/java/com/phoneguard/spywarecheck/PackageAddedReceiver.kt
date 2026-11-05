package com.phoneguard.spywarecheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phoneguard.util.NotificationChannelInstaller
import com.phoneguard.R
import com.phoneguard.malware.ApkSignatureScanner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Реагирует на установку стороннего APK.
 *
 * Раньше уведомление было безусловным («Установлено новое приложение»), из-за
 * чего пользователь переставал их читать. Теперь подпись APK разбирается
 * локально через [ApkSignatureScanner] (apk-parser) и уведомление о подписи
 * показывается только при реальной проблеме: debug-ключ у приложения, слабый
 * алгоритм (MD5/SHA-1) или истёкший сертификат.
 */
@AndroidEntryPoint
class PackageAddedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var apkSignatureScanner: ApkSignatureScanner

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
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
                val signature = runCatching {
                    apkSignatureScanner.inspectInstalledPackage(packageName)
                }.getOrNull()

                if (signature != null && (
                        signature.isInstalledFromDebugKey ||
                            signature.hasWeakSignature ||
                            signature.isCertificateExpired
                        )
                ) {
                    notify(
                        context = context,
                        packageName = packageName,
                        title = context.getString(R.string.new_app_suspicious_title),
                        text = context.getString(R.string.new_app_suspicious_text, appName)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Signature inspection failed for $packageName", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notify(context: Context, packageName: String, title: String, text: String) {
        createNotificationChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // Не adaptive-иконка: её foreground в статус-баре выглядит пустым пятном.
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            // Отдельный id на пакет: раньше фиксированный id заставлял уведомление
            // о второй подозрительной установке затирать первое.
            manager.notify(notificationIdFor(packageName), notification)
        } catch (e: SecurityException) {
            // На Android 13+ без POST_NOTIFICATIONS уведомление просто не показываем.
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    private fun createNotificationChannel(context: Context) {
        NotificationChannelInstaller.install(
            context,
            com.phoneguard.util.NotificationChannels.Spec.NEW_APP,
            context.getString(R.string.new_app_channel_name)
        )
    }

    companion object {
        private const val TAG = "PackageAddedReceiver"
        private val CHANNEL_ID: String =
            com.phoneguard.util.NotificationChannels.Spec.NEW_APP.channelId

        /**
         * Уведомление на каждый пакет, а не одно общее.
         *
         * Диапазон живёт в [com.phoneguard.util.NotificationIds]: прежний
         * `2000..6095` накрывал `4101` — подтверждение подмены SIM, и установка
         * приложения могла стереть предупреждение о подмене.
         */
        fun notificationIdFor(packageName: String): Int =
            com.phoneguard.util.NotificationIds.packageAdded(packageName)
    }
}
