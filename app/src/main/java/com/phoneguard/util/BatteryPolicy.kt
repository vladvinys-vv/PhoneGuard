package com.phoneguard.util

/**
 * Пороги батареи и интервалы проверок — без Android.
 *
 * Вынесено из [BatteryOptimizationHelper]: там это были два `when`-а внутри класса, который
 * регистрирует `BroadcastReceiver` и читает `BatteryManager`. Проверить их в JVM было нельзя,
 * хотя цена ошибки — расход батареи у пользователя (слишком частые сканы) или пропущенная
 * атака (слишком редкие).
 *
 * Уровень заряда всегда приходит через [sanitizeLevel]: `BatteryManager.getIntProperty()`
 * возвращает `Int.MIN_VALUE`, если значение недоступно (некоторые прошивки и эмуляторы),
 * и раньше это читалось как «заряд ≤ 15» — приложение уходило в самый экономный режим.
 */
internal object BatteryPolicy {

    const val LOW_BATTERY_THRESHOLD = 15
    const val CRITICAL_BATTERY_LEVEL = 10
    const val HALF_BATTERY_LEVEL = 50
    const val MAX_BATTERY_LEVEL = 100

    /** Что считаем уровнем, когда API не отдал значение: «полный заряд» (не экономим зря). */
    const val UNKNOWN_BATTERY_LEVEL = MAX_BATTERY_LEVEL

    private const val HOUR_MS = 60L * 60L * 1000L

    /** Приводит «сырое» значение из `BatteryManager` к проценту: всё вне `0..100` → «неизвестно». */
    fun sanitizeLevel(rawCapacity: Int): Int =
        if (rawCapacity in 0..MAX_BATTERY_LEVEL) rawCapacity else UNKNOWN_BATTERY_LEVEL

    fun isLow(level: Int): Boolean = level <= LOW_BATTERY_THRESHOLD

    /**
     * Интервал планового полного сканирования.
     *
     * Порядок важен: сначала низкий заряд (экономим всё), затем «не заряжается» — 6 часов,
     * и только на зарядке можно позволить себе скан каждые 3 часа.
     */
    fun scanInterval(level: Int, charging: Boolean): Long = when {
        isLow(level) -> 12L * HOUR_MS
        !charging -> 6L * HOUR_MS
        else -> 3L * HOUR_MS
    }

    /**
     * Интервал кадров детектора «подглядывающего».
     *
     * Порядок веток — исправленный: критически низкий заряд, затем **зарядка** (можно опрашивать
     * часто), и только потом остальные уровни. Раньше ветка зарядки стояла после проверок уровня,
     * и на зарядке с 40 % частота оставалась 4 с вместо 1.5 с.
     */
    fun shoulderSurferInterval(level: Int, charging: Boolean): Long = when {
        level <= CRITICAL_BATTERY_LEVEL -> 10_000L
        charging -> 1_500L
        level <= LOW_BATTERY_THRESHOLD -> 6_000L
        level <= HALF_BATTERY_LEVEL -> 4_000L
        else -> 2_500L
    }
}
