package com.phoneguard.simswap

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phoneguard.R

/**
 * Уведомление «Вы поменяли SIM?».
 *
 * Раньше [SimSwapDetector] только отправлял событие в `uiEvents` — горячий `SharedFlow`
 * без replay. Подмена SIM обычно происходит, когда приложение закрыто, поэтому
 * подтверждение никто не видел, а объявленная в манифесте
 * [SimSwapConfirmationActivity] вообще ниоткуда не запускалась.
 *
 * Теперь после обнаружения подмены показывается уведомление, тап по которому открывает
 * экран подтверждения (в том числе поверх блокировки экрана).
 */
object SimSwapNotifications {

    const val CHANNEL_ID = "sim_swap_channel"

    /** Отдельный id: не пересекается с VPN (1001), звонками (1101/1102) и анти-вором. */
    const val SIM_SWAP_NOTIFICATION_ID = com.phoneguard.util.NotificationIds.SIM_SWAP

    /** Показывает уведомление о неподтверждённой смене SIM. */
    fun notifyUnconfirmedSwap(context: Context) {
        ensureChannel(context)
        notifySafely(context)
    }

    /** Убирает уведомление после подтверждения/отклонения смены SIM. */
    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(SIM_SWAP_NOTIFICATION_ID)
        }
    }

    private fun notifySafely(context: Context) {
        // POST_NOTIFICATIONS (Android 13+) — без разрешения notify() бросает SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, SimSwapConfirmationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SIM_SWAP_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.sim_swap_confirm_title))
            .setContentText(context.getString(R.string.sim_swap_confirm_description))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(SIM_SWAP_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sim_swap_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.sim_swap_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}
