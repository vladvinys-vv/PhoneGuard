package com.phoneguard.antitheft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ответ на удалённую команду `STATUS`: коды `TelephonyManager.SIM_STATE_*` и
 * включение строки про батарею.
 */
class AntitheftStatusPolicyTest {

    @Test
    fun `коды состояний SIM разбираются по значениям платформы`() {
        // Значения из TelephonyManager (стабильны с API 1).
        assertEquals(AntitheftStatusPolicy.SimState.UNKNOWN, AntitheftStatusPolicy.simStateOf(0))
        assertEquals(AntitheftStatusPolicy.SimState.ABSENT, AntitheftStatusPolicy.simStateOf(1))
        assertEquals(AntitheftStatusPolicy.SimState.PIN_REQUIRED, AntitheftStatusPolicy.simStateOf(2))
        assertEquals(AntitheftStatusPolicy.SimState.PUK_REQUIRED, AntitheftStatusPolicy.simStateOf(3))
        assertEquals(AntitheftStatusPolicy.SimState.NETWORK_LOCKED, AntitheftStatusPolicy.simStateOf(4))
        assertEquals(AntitheftStatusPolicy.SimState.READY, AntitheftStatusPolicy.simStateOf(5))
    }

    @Test
    fun `прочие состояния считаются неизвестными`() {
        // NOT_READY=6, PERM_DISABLED=7, CARD_IO_ERROR=8, LOADED=10, POWER_OFF=11.
        listOf(6, 7, 8, 9, 10, 11, -1).forEach { value ->
            assertEquals("код $value", AntitheftStatusPolicy.SimState.UNKNOWN, AntitheftStatusPolicy.simStateOf(value))
        }
    }

    @Test
    fun `PIN и PUK не перепутаны`() {
        // Отдельный тест на самую дорогую ошибку в этом маппинге.
        assertTrue(AntitheftStatusPolicy.simStateOf(2) != AntitheftStatusPolicy.simStateOf(3))
    }

    @Test
    fun `уровень батареи добавляется только при валидном значении`() {
        assertTrue(AntitheftStatusPolicy.includeBatteryLevel(0))
        assertTrue(AntitheftStatusPolicy.includeBatteryLevel(57))
        assertTrue(AntitheftStatusPolicy.includeBatteryLevel(100))
        // -1 = EXTRA_LEVEL не пришёл.
        assertFalse(AntitheftStatusPolicy.includeBatteryLevel(-1))
        assertFalse(AntitheftStatusPolicy.includeBatteryLevel(101))
    }
}
