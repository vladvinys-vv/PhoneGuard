package com.phoneguard.intrusion

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phoneguard.R
import com.phoneguard.ui.IntrusionWarningActivity
import com.phoneguard.util.NotificationChannelInstaller
import com.phoneguard.util.NotificationChannels
import com.phoneguard.util.NotificationIds

/**
 * Уведомление о вторжении — канал доставки для фонового режима.
 *
 * Повод: `IntrusionResponseExecutor` показывал предупреждение только запуском активити, а
 * начиная с Android 10 запуск из фона молча блокируется — предупреждение не видел никто
 * (см. [IntrusionWarningDeliveryPolicy]). Уведомление показывается всегда и открывает тот же
 * экран с кнопкой «Остановить сигнал».
 */
internal object IntrusionWarningNotifier {

    val CHANNEL_ID: String = NotificationChannels.Spec.INTRUSION_WARNING.channelId

    /** Показывает предупреждение уведомлением. Возвращает `true`, если уведомление ушло. */
    fun notify(context: Context, title: String, message: String): Boolean {
        // POST_NOTIFICATIONS (Android 13+) — без разрешения notify() бросает SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        ensureChannel(context)

        val intent = Intent(context, IntrusionWarningActivity::class.java).apply {
            putExtra(IntrusionWarningActivity.EXTRA_TITLE, title)
            putExtra(IntrusionWarningActivity.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NotificationIds.INTRUSION_WARNING,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(NotificationIds.INTRUSION_WARNING, notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(context: Context) {
        // Раньше здесь стоял ранний `return` при существующем канале, поэтому правки имени и
        // описания (в том числе переводы) не доезжали до тех, у кого канал уже был создан.
        NotificationChannelInstaller.install(
            context,
            NotificationChannels.Spec.INTRUSION_WARNING,
            context.getString(R.string.intrusion_warning_channel_name),
            context.getString(R.string.intrusion_warning_channel_description)
        )
    }
}
