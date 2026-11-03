package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пороги батареи и интервалы проверок.
 *
 * Цена ошибки здесь двусторонняя: слишком частые проверки съедают заряд (пользователь удаляет
 * приложение), слишком редкие — пропускают атаку. Отдельно проверяется «мусорный» уровень заряда:
 * `BatteryManager.getIntProperty()` возвращает `Int.MIN_VALUE`, когда значение недоступно.
 */
class BatteryPolicyTest {

    private val hour = 60L * 60L * 1000L

    // --- нормализация уровня -------------------------------------------------

    @Test
    fun validCapacityIsKept() {
        assertEquals(0, BatteryPolicy.sanitizeLevel(0))
        assertEquals(37, BatteryPolicy.sanitizeLevel(37))
        assertEquals(100, BatteryPolicy.sanitizeLevel(100))
    }

    @Test
    fun unavailableCapacityBecomesFullBattery() {
        // Int.MIN_VALUE раньше читалось как «заряд ≤ 15» и включало самый экономный режим.
        assertEquals(BatteryPolicy.MAX_BATTERY_LEVEL, BatteryPolicy.sanitizeLevel(Int.MIN_VALUE))
    }

    @Test
    fun outOfRangeCapacityBecomesFullBattery() {
        assertEquals(BatteryPolicy.MAX_BATTERY_LEVEL, BatteryPolicy.sanitizeLevel(-1))
        assertEquals(BatteryPolicy.MAX_BATTERY_LEVEL, BatteryPolicy.sanitizeLevel(101))
        assertEquals(BatteryPolicy.MAX_BATTERY_LEVEL, BatteryPolicy.sanitizeLevel(Int.MAX_VALUE))
    }

    // --- низкий заряд --------------------------------------------------------

    @Test
    fun lowBatteryBoundaryIsInclusive() {
        assertTrue(BatteryPolicy.isLow(15))
        assertTrue(BatteryPolicy.isLow(0))
        assertFalse(BatteryPolicy.isLow(16))
        assertFalse(BatteryPolicy.isLow(100))
    }

    // --- интервал планового скана -------------------------------------------

    @Test
    fun lowBatteryScanIntervalWinsOverChargingState() {
        // even while charging: 12 h — заряд дороже свежести данных
        assertEquals(12 * hour, BatteryPolicy.scanInterval(5, charging = true))
        assertEquals(12 * hour, BatteryPolicy.scanInterval(15, charging = true))
    }

    @Test
    fun dischargingUsesSixHours() {
        assertEquals(6 * hour, BatteryPolicy.scanInterval(16, charging = false))
        assertEquals(6 * hour, BatteryPolicy.scanInterval(100, charging = false))
    }

    @Test
    fun chargingUsesThreeHours() {
        assertEquals(3 * hour, BatteryPolicy.scanInterval(16, charging = true))
        assertEquals(3 * hour, BatteryPolicy.scanInterval(100, charging = true))
    }

    // --- интервал кадров детектора «подглядывающего» -------------------------

    @Test
    fun criticalBatterySlowsDetectorEvenWhileCharging() {
        assertEquals(10_000L, BatteryPolicy.shoulderSurferInterval(10, charging = true))
        assertEquals(10_000L, BatteryPolicy.shoulderSurferInterval(0, charging = false))
    }

    @Test
    fun chargingBeatsModerateBatteryLevels() {
        // Раньше ветка «заряжается» стояла после проверок уровня, и на 40 % было 4 с вместо 1.5 с.
        assertEquals(1_500L, BatteryPolicy.shoulderSurferInterval(40, charging = true))
        assertEquals(1_500L, BatteryPolicy.shoulderSurferInterval(11, charging = true))
        assertEquals(1_500L, BatteryPolicy.shoulderSurferInterval(100, charging = true))
    }

    @Test
    fun dischargingUsesLevelTable() {
        assertEquals(6_000L, BatteryPolicy.shoulderSurferInterval(11, charging = false))
        assertEquals(6_000L, BatteryPolicy.shoulderSurferInterval(15, charging = false))
        assertEquals(4_000L, BatteryPolicy.shoulderSurferInterval(16, charging = false))
        assertEquals(4_000L, BatteryPolicy.shoulderSurferInterval(50, charging = false))
        assertEquals(2_500L, BatteryPolicy.shoulderSurferInterval(51, charging = false))
        assertEquals(2_500L, BatteryPolicy.shoulderSurferInterval(100, charging = false))
    }

    @Test
    fun unknownLevelNeverActivatesPowerSaving() {
        val level = BatteryPolicy.sanitizeLevel(Int.MIN_VALUE)
        assertEquals(2_500L, BatteryPolicy.shoulderSurferInterval(level, charging = false))
        assertEquals(3 * hour, BatteryPolicy.scanInterval(level, charging = true))
    }
}
