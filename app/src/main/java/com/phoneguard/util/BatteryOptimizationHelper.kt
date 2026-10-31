package com.phoneguard.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Адаптация частоты проверок к состоянию батареи.
 *
 * Исправления:
 *  - `getIntProperty(BATTERY_PROPERTY_CAPACITY)` возвращает `Int.MIN_VALUE`, если
 *    значение недоступно (некоторые прошивки/эмуляторы). Раньше это трактовалось как
 *    «заряд ≤ 15» и «уровень ≤ 10»: сканирование уходило в самый экономный режим, а
 *    детектор «подглядывающего» включался раз в 10 секунд;
 *  - ветка «заряжается» в `getAdaptiveShoulderSurferInterval()` стояла **после**
 *    проверок уровня, поэтому при зарядке на 40 % частота оставалась 4 с, хотя на
 *    зарядке телефон может позволить себе опрос чаще.
 */
@Singleton
class BatteryOptimizationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun isBatteryLow(): Boolean = getBatteryLevel() <= LOW_BATTERY_THRESHOLD

    fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Уровень заряда в процентах; если прочитать нельзя — считаем, что заряд полный. */
    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return UNKNOWN_BATTERY_LEVEL
        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (capacity in 0..MAX_BATTERY_LEVEL) capacity else UNKNOWN_BATTERY_LEVEL
    }

    fun getAdaptiveScanInterval(): Long = when {
        isBatteryLow() -> 12L * 60L * 60L * 1000L
        !isCharging() -> 6L * 60L * 60L * 1000L
        else -> 3L * 60L * 60L * 1000L
    }

    /**
     * Интервал кадров детектора «подглядывающего».
     *
     * Порядок важен: сначала — критически низкий заряд (экономим всё), затем зарядка
     * (можно опрашивать часто), затем остальные уровни.
     */
    fun getAdaptiveShoulderSurferInterval(): Long {
        val level = getBatteryLevel()
        return when {
            level <= CRITICAL_BATTERY_LEVEL -> 10_000L
            isCharging() -> 1_500L
            level <= LOW_BATTERY_THRESHOLD -> 6_000L
            level <= HALF_BATTERY_LEVEL -> 4_000L
            else -> 2_500L
        }
    }

    private companion object {
        const val LOW_BATTERY_THRESHOLD = 15
        const val CRITICAL_BATTERY_LEVEL = 10
        const val HALF_BATTERY_LEVEL = 50
        const val MAX_BATTERY_LEVEL = 100

        /** Значение-заглушка, когда API не отдал уровень заряда. */
        const val UNKNOWN_BATTERY_LEVEL = MAX_BATTERY_LEVEL
    }
}
