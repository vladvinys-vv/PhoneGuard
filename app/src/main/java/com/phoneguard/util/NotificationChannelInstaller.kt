package com.phoneguard.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Регистрация каналов уведомлений на устройстве (Android-часть [NotificationChannels]).
 *
 * Отличие от прежнего кода в трёх местах:
 *  * **нет раннего `return` при существующем канале.** Имя и описание система обновляет
 *    и для уже созданного канала, поэтому переводы и правки текста доезжают до пользователя;
 *  * важность и вибрация выставляются из таблицы [NotificationChannels.Spec], а не из
 *    константы в каждом сервисе;
 *  * если канал на устройстве **тише**, чем нужно (создан старой версией или приглушён
 *    пользователем), это пишется в журнал: важность существующего канала Android менять
 *    не позволяет, но молчать об этом нельзя — предупреждения просто не будут слышны.
 */
internal object NotificationChannelInstaller {

    private const val TAG = "PhoneGuardChannels"

    /** Создаёт канал или обновляет его имя/описание. Безопасно вызывать сколько угодно раз. */
    fun install(
        context: Context,
        spec: NotificationChannels.Spec,
        name: String,
        description: String? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(spec.channelId, name, spec.importance.toPlatformImportance())
        channel.description = description
        if (spec.vibrates) {
            channel.enableVibration(true)
            channel.vibrationPattern = VIBRATION_PATTERN
        }

        runCatching { manager.createNotificationChannel(channel) }
            .onFailure { Log.w(TAG, "Cannot create channel ${spec.channelId}", it) }

        reportSilenced(context, manager)
    }

    /** Отменяет перечисленные уведомления; отсутствующие просто игнорируются. */
    fun cancel(context: Context, ids: List<Int>) {
        if (ids.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        ids.forEach { id ->
            runCatching { manager.cancel(id) }
        }
    }

    private fun reportSilenced(context: Context, manager: NotificationManager) {
        val existing = runCatching { manager.notificationChannels }
            .getOrDefault(emptyList())
            .map { NotificationChannels.ExistingChannel(it.id, it.importance.toRank()) }
        NotificationChannels.silenced(existing).forEach { spec ->
            Log.w(
                TAG,
                "Канал ${spec.channelId} на устройстве тише, чем нужно " +
                    "(${spec.importance.name}): предупреждения могут быть не слышны"
            )
        }
    }

    private val VIBRATION_PATTERN = longArrayOf(0, 250, 250, 250)
}

/** Важность приложения → константа платформы. */
private fun NotificationChannels.Importance.toPlatformImportance(): Int = when (this) {
    NotificationChannels.Importance.MIN -> NotificationManager.IMPORTANCE_MIN
    NotificationChannels.Importance.LOW -> NotificationManager.IMPORTANCE_LOW
    NotificationChannels.Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
    NotificationChannels.Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
}

/**
 * Важность платформы → rank приложения.
 *
 * Неизвестные значения (в том числе `IMPORTANCE_NONE` = канал выключен пользователем)
 * считаются «тише любого требования» — такой канал тоже должен попасть в отчёт.
 */
private fun Int.toRank(): Int = when (this) {
    NotificationManager.IMPORTANCE_MIN -> NotificationChannels.Importance.MIN.rank
    NotificationManager.IMPORTANCE_LOW -> NotificationChannels.Importance.LOW.rank
    NotificationManager.IMPORTANCE_DEFAULT -> NotificationChannels.Importance.DEFAULT.rank
    NotificationManager.IMPORTANCE_HIGH -> NotificationChannels.Importance.HIGH.rank
    else -> -1
}
