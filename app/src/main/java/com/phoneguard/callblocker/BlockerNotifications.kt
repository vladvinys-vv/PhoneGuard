package com.phoneguard.callblocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phoneguard.R
import com.phoneguard.ui.MainActivity

/**
 * Канал и уведомления блокировщика звонков/SMS.
 *
 * Раньше канал и уведомление создавались только внутри `CallScreeningServiceImpl`,
 * а id уведомления (`1001`) совпадал с id постоянного уведомления VPN — статус
 * брандмауэра затирался сообщением о заблокированном звонке. Теперь id в одном
 * месте и не пересекаются с [com.phoneguard.firewall.PhoneGuardVpnService].
 */
object BlockerNotifications {

    const val CHANNEL_ID = "call_blocker_channel"

    /** Заблокированный входящий звонок. */
    const val CALL_NOTIFICATION_ID = com.phoneguard.util.NotificationIds.BLOCKED_CALL

    /** Заблокированное входящее SMS (отдельный id, чтобы не затирать звонок). */
    const val SMS_NOTIFICATION_ID = com.phoneguard.util.NotificationIds.BLOCKED_SMS

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.call_blocker_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.call_blocker_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /** Показывает короткое тихое уведомление о блокировке. */
    fun show(context: Context, notificationId: Int, title: String, text: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Отдельный id на каждый тип блокировки: иначе «SMS заблокировано»
        // заменяло «звонок заблокирован» и наоборот.
        runCatching { manager.notify(notificationId, notification) }
    }
}
