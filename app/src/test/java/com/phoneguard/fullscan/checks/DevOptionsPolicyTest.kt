package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Оценка риска «параметры разработчика / отладка» — теперь проверяется на JVM. */
class DevOptionsPolicyTest {

    @Test
    fun `всё выключено — LOW`() {
        assertEquals(
            DevOptionsPolicy.Level.LOW,
            DevOptionsPolicy.assess(DevOptionsPolicy.State())
        )
    }

    @Test
    fun `только параметры разработчика — MEDIUM`() {
        val state = DevOptionsPolicy.State(developerOptions = true)
        assertEquals(DevOptionsPolicy.Level.MEDIUM, DevOptionsPolicy.assess(state))
    }

    @Test
    fun `USB-отладка с параметрами разработчика — HIGH`() {
        val state = DevOptionsPolicy.State(usbDebugging = true, developerOptions = true)
        assertEquals(DevOptionsPolicy.Level.HIGH, DevOptionsPolicy.assess(state))
    }

    @Test
    fun `отладка по Wi-Fi с параметрами разработчика — тоже HIGH`() {
        // Раньше отдельный ключ `adb_wifi_enabled` не читался вообще, и отчёт
        // показывал «ничего не включено», хотя ADB по сети был доступен.
        val state = DevOptionsPolicy.State(wirelessDebugging = true, developerOptions = true)
        assertEquals(DevOptionsPolicy.Level.HIGH, DevOptionsPolicy.assess(state))
        assertTrue(DevOptionsPolicy.hasAdbAccess(state))
    }

    @Test
    fun `отладка без параметров разработчика — MEDIUM`() {
        assertEquals(
            DevOptionsPolicy.Level.MEDIUM,
            DevOptionsPolicy.assess(DevOptionsPolicy.State(usbDebugging = true))
        )
    }

    @Test
    fun `ADB не считается доступным при выключенных отладках`() {
        val state = DevOptionsPolicy.State(developerOptions = true)
        assertFalse(DevOptionsPolicy.hasAdbAccess(state))
    }

    @Test
    fun `уровни совпадают с именами строк отчёта`() {
        // riskLevel уходит в DevOptionsResult строкой — имена должны остаться прежними.
        assertEquals("LOW", DevOptionsPolicy.Level.LOW.name)
        assertEquals("MEDIUM", DevOptionsPolicy.Level.MEDIUM.name)
        assertEquals("HIGH", DevOptionsPolicy.Level.HIGH.name)
    }
}
