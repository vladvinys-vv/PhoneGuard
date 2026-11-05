package com.phoneguard.util

/**
 * Можно ли поднять foreground-сервис из данного места и что делать, если нельзя.
 *
 * С Android 12 (`S`) система запрещает запуск foreground-сервиса из фона и бросает
 * `ForegroundServiceStartNotAllowedException`. В коде это уже трижды обрабатывалось
 * по-своему: `ShoulderSurferService.start()` возвращает `false`, `FirewallViewModel`
 * молча сбрасывает «включено», `SmsCommandReceiver` отвечает SMS об ошибке. При этом
 * `stop()` у сервиса и `stopVpn()` у брандмауэра вызывали `startService()` — из фона он
 * бросает `IllegalStateException`, и команда «остановить» тихо терялась: интерфейс
 * показывал «выключено», а сервис продолжал работать.
 *
 * Здесь собраны исключения, на которые можно рассчитывать, и честные запасные варианты.
 * Правила чистые (никакого `Context`), поэтому проверяются JVM-тестом.
 */
object BackgroundStartPolicy {

    /** Откуда пришла команда запуска. */
    enum class Trigger {
        /** Пользователь нажал что-то в интерфейсе (экран, виджет, уведомление). */
        USER_VISIBLE,

        /** Приложение — device admin (анти-кража): такие приложения исключены из ограничений. */
        DEVICE_ADMIN,

        /** Системный broadcast загрузки (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`). */
        BOOT_COMPLETED,

        /** Входящее SMS с командой владельца. */
        SMS_COMMAND,

        /** Периодическая работа (WorkManager, планировщик сканирований). */
        WORKER,

        /** Источник неизвестен — считаем фоном. */
        UNKNOWN
    }

    /** Что делать, если система всё-таки отказала в запуске. */
    enum class Fallback {
        /** Ошибка видна пользователю: интерфейс не должен показывать «включено». */
        REPORT_TO_USER,

        /** Ответить нельзя (SMS уже отправлено) — показать локальное уведомление. */
        NOTIFY_INSTEAD,

        /** Отложить до следующего разрешённого момента (повторная работа). */
        DEFER
    }

    /** Версия Android, с которой действуют ограничения фонового запуска сервисов. */
    const val RESTRICTIONS_FROM_SDK = 31

    /**
     * Разрешён ли запуск foreground-сервиса.
     *
     * @param isDeviceAdmin активен ли наш device admin — это исключение из ограничений
     *   (именно поэтому анти-кража может звучать по SMS-команде на Android 12+).
     */
    fun mayStartForeground(trigger: Trigger, sdkInt: Int, isDeviceAdmin: Boolean): Boolean {
        if (sdkInt < RESTRICTIONS_FROM_SDK) return true
        return when (trigger) {
            Trigger.USER_VISIBLE -> true
            Trigger.BOOT_COMPLETED -> true
            Trigger.DEVICE_ADMIN -> isDeviceAdmin
            Trigger.SMS_COMMAND -> isDeviceAdmin
            Trigger.WORKER -> false
            Trigger.UNKNOWN -> false
        }
    }

    /** Запасной вариант для [trigger], если запуск отклонён. */
    fun fallback(trigger: Trigger): Fallback = when (trigger) {
        Trigger.USER_VISIBLE, Trigger.DEVICE_ADMIN -> Fallback.REPORT_TO_USER
        Trigger.SMS_COMMAND, Trigger.UNKNOWN -> Fallback.NOTIFY_INSTEAD
        Trigger.BOOT_COMPLETED, Trigger.WORKER -> Fallback.DEFER
    }

    /**
     * Нужно ли вообще пытаться: попытка разрешена, но может быть отклонена системой.
     *
     * `true` не гарантирует успех — вызывающий код обязан ловить исключение. `false`
     * означает, что пытаться бессмысленно и надо сразу идти в [fallback].
     */
    fun shouldAttempt(trigger: Trigger, sdkInt: Int, isDeviceAdmin: Boolean): Boolean =
        mayStartForeground(trigger, sdkInt, isDeviceAdmin)
}
