package com.phoneguard.util

/**
 * Все каналы уведомлений приложения — одной таблицей, плюс правила их «залипания».
 *
 * Раньше идентификатор и важность каждого канала были `private const` в своём сервисе или
 * ресивере (девять мест), а `ensureChannel()` в трёх из них начинался с
 * `if (getNotificationChannel(id) != null) return`. Из-за этого:
 *
 *  * переименование канала и правка его описания (в том числе переводы) **никогда** не доезжали
 *    до пользователя — канал создавался один раз в первой версии и жил с тем текстом;
 *  * уведомление о вторжении никто не отменял: `stopAlarm()` останавливал сирену, а
 *    предупреждение оставалось висеть, пока пользователь не тронет его пальцем;
 *  * то же с предупреждением «кто-то смотрит в экран»: сервис останавливался, уведомление
 *    оставалось.
 *
 * Здесь только чистые правила (никакого `Context`), поэтому они проверяются JVM-тестом.
 * Android-часть — `NotificationChannelInstaller`.
 */
object NotificationChannels {

    /** Важность канала в терминах приложения (значения `NotificationManager`). */
    enum class Importance(val rank: Int) {
        MIN(0),
        LOW(1),
        DEFAULT(2),
        HIGH(3)
    }

    /**
     * Канал уведомлений.
     *
     * `channelId` менять нельзя: вместе с ним теряются пользовательские настройки канала
     * (звук, вибрация, «не беспокоить»), которые система хранит по id.
     */
    enum class Spec(
        val channelId: String,
        val importance: Importance,
        val vibrates: Boolean = false
    ) {
        /** Постоянное уведомление VPN-брандмауэра. */
        FIREWALL("phoneguard_vpn_channel", Importance.LOW),

        /** Постоянное уведомление скрытой съёмки — максимально незаметное. */
        STEALTH_CAMERA("stealth_camera_channel", Importance.MIN),

        /** Постоянное уведомление детектора подглядывания. */
        SHOULDER_SURFER("shoulder_surfer_channel", Importance.LOW),

        /** Предупреждение «кто-то смотрит в экран». */
        SHOULDER_SURFER_ALERT("shoulder_surfer_alert_channel", Importance.HIGH, vibrates = true),

        /** Заблокированные звонки и SMS. */
        BLOCKED("call_blocker_channel", Importance.LOW),

        /** Предупреждение о вторжении. */
        INTRUSION_WARNING("intrusion_warning_channel", Importance.HIGH),

        /** Попытка управлять телефоном с неверным PIN. */
        SMS_COMMANDS("sms_command_channel", Importance.HIGH),

        /** «Вы поменяли SIM?» */
        SIM_SWAP("sim_swap_channel", Importance.HIGH),

        /** Подозрительная подпись только что установленного приложения. */
        NEW_APP("package_added_channel", Importance.DEFAULT);

        companion object {
            fun byChannelId(channelId: String): Spec? =
                values().firstOrNull { it.channelId == channelId }
        }
    }

    /** Уже существующий канал: id и фактическая важность (rank из [Importance]). */
    data class ExistingChannel(val channelId: String, val importanceRank: Int)

    /**
     * Каналы, которые на устройстве тише, чем нужно приложению.
     *
     * Важность существующего канала система менять не даёт — это осознанное ограничение
     * Android. Значит, если канал когда-то был создан с меньшей важностью (старая версия
     * приложения) или пользователь приглушил его, предупреждения будут тихими, и приложение
     * об этом должно хотя бы знать, а не молчать. Возвращаются только реально приглушённые
     * каналы, в порядке объявления [Spec].
     */
    fun silenced(existing: List<ExistingChannel>): List<Spec> {
        val byId = existing.associate { it.channelId to it.importanceRank }
        return Spec.values().filter { spec ->
            val actual = byId[spec.channelId] ?: return@filter false
            actual < spec.importance.rank
        }
    }

    /** Какие постоянные каналы существуют на устройстве (для отчёта и проверок). */
    fun known(existing: List<ExistingChannel>): List<Spec> =
        existing.mapNotNull { Spec.byChannelId(it.channelId) }

    /**
     * Событие, после которого временные уведомления обязаны исчезнуть.
     *
     * `setAutoCancel(true)` убирает уведомление только по тапу. Если пользователь остановил
     * сирену кнопкой в приложении или сервис завершился сам, уведомление оставалось висеть —
     * и выглядело как продолжающееся событие.
     */
    enum class StopReason {
        /** Сирену остановили (кнопка «Остановить сигнал», экран настроек). */
        ALARM_STOPPED,

        /** Сирена отзвучала заданное время сама. */
        SIREN_EXPIRED,

        /** Детектор подглядывания остановлен (выключили функцию или сервис умер). */
        DETECTION_STOPPED
    }

    /** Идентификаторы уведомлений, которые нужно отменить для [reason] (без повторов). */
    fun cancelledIds(reason: StopReason): List<Int> = when (reason) {
        StopReason.ALARM_STOPPED, StopReason.SIREN_EXPIRED ->
            listOf(NotificationIds.INTRUSION_WARNING)
        StopReason.DETECTION_STOPPED ->
            listOf(NotificationIds.SHOULDER_SURFER_ALERT)
    }.distinct()
}
