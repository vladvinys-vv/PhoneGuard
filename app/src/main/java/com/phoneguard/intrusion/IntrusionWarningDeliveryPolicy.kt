package com.phoneguard.intrusion

/** Как доставить предупреждение о вторжении. */
internal enum class WarningDelivery {
    /** Открыть экран `IntrusionWarningActivity` — работает только когда приложение на переднем плане. */
    ACTIVITY,

    /** Уведомление — единственный канал, доступный приложению в фоне. */
    NOTIFICATION
}

/**
 * Выбор канала доставки предупреждения о вторжении.
 *
 * Раньше `IntrusionResponseExecutor.showWarning()` всегда вызывал
 * `IntrusionWarningActivity.start()`, то есть `Context.startActivity()` из фонового потока
 * сервиса. Начиная с Android 10 (API 29) запуск активити из фона **молча блокируется**:
 * исключения нет, `startActivity` возвращает управление, а экран не появляется. В результате
 * предупреждение о вторжении не видел никто — ровно то событие, ради которого оно и делалось
 * (приманку открыл вредонос, кто-то трогает телефон).
 *
 * Правило: на переднем плане — экран (он заметнее и у него есть кнопка «остановить тревогу»),
 * в фоне — уведомление, которое система показывает всегда.
 */
internal object IntrusionWarningDeliveryPolicy {

    /** С этой версии Android запуск активити из фона запрещён (исключения не бросается). */
    const val BACKGROUND_ACTIVITY_LOCK_SDK = 29

    fun choose(sdkInt: Int, appInForeground: Boolean): WarningDelivery = when {
        appInForeground -> WarningDelivery.ACTIVITY
        // До Android 10 фоновый запуск активити разрешён — экран остаётся лучшим каналом.
        sdkInt < BACKGROUND_ACTIVITY_LOCK_SDK -> WarningDelivery.ACTIVITY
        else -> WarningDelivery.NOTIFICATION
    }
}
