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

    fun isBatteryLow(): Boolean = BatteryPolicy.isLow(getBatteryLevel())

    fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Уровень заряда в процентах; если прочитать нельзя — считаем, что заряд полный. */
    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return BatteryPolicy.UNKNOWN_BATTERY_LEVEL
        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // Int.MIN_VALUE и прочие «мусорные» значения → «неизвестно» (= полный заряд).
        return BatteryPolicy.sanitizeLevel(capacity)
    }

    /** Пороги и интервалы — в чистом [BatteryPolicy] (там же тесты). */
    fun getAdaptiveScanInterval(): Long =
        BatteryPolicy.scanInterval(getBatteryLevel(), isCharging())

    /**
     * Интервал кадров детектора «подглядывающего».
     *
     * Порядок важен: сначала — критически низкий заряд (экономим всё), затем зарядка
     * (можно опрашивать часто), затем остальные уровни.
     */
    fun getAdaptiveShoulderSurferInterval(): Long {
        val level = getBatteryLevel()
        // Статус зарядки нужен только вне критической ветки: `registerReceiver` не бесплатный,
        // а при заряде ≤ 10 % ответ от него всё равно не используется.
        val charging = level > BatteryPolicy.CRITICAL_BATTERY_LEVEL && isCharging()
        return BatteryPolicy.shoulderSurferInterval(level, charging)
    }
}
