package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Эвристики окружения: ложная тревога здесь стоит дорого — `RAT_DETECTED` уровня
 * `HIGH` запускает автоматическую реакцию (сирена, фото, SMS доверенным контактам).
 */
class DeviceHeuristicsTest {

    @Test
    fun knownHookFrameworksAreDetected() {
        assertTrue(DeviceHeuristics.isSuspiciousPackage("de.robv.android.xposed"))
        assertTrue(DeviceHeuristics.isSuspiciousPackage("re.frida.server"))
        assertTrue(DeviceHeuristics.isSuspiciousPackage("com.topjohnwu.magisk"))
        assertTrue(DeviceHeuristics.isSuspiciousPackage("org.lsposed.manager"))
        assertTrue(DeviceHeuristics.isSuspiciousPackage("com.example.cydia"))
    }

    @Test
    fun similarLookingAppNamesAreNotFlagged() {
        // Именно эти имена ловил прежний `contains("frida")` / `contains("cydia")`.
        assertFalse(DeviceHeuristics.isSuspiciousPackage("com.fridays.app"))
        assertFalse(DeviceHeuristics.isSuspiciousPackage("com.example.cydiabrowser"))
        assertFalse(DeviceHeuristics.isSuspiciousPackage("com.example.magiskhelper"))
        assertFalse(DeviceHeuristics.isSuspiciousPackage("com.xposeddetector"))
        assertFalse(DeviceHeuristics.isSuspiciousPackage("org.wikipedia"))
        assertFalse(DeviceHeuristics.isSuspiciousPackage(null))
    }

    @Test
    fun accessibilitySettingIsParsedAndDeduplicated() {
        val raw = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService:" +
            "com.evil.spy/com.evil.spy.Service:com.evil.spy/com.evil.spy.OtherService"

        assertEquals(
            listOf("com.evil.spy"),
            DeviceHeuristics.untrustedAccessibilityPackages(raw, "com.phoneguard")
        )
    }

    @Test
    fun ownPackageIsNotFlagged() {
        val raw = "com.phoneguard/com.phoneguard.accessibility.PinService"
        assertTrue(
            DeviceHeuristics.untrustedAccessibilityPackages(raw, "com.phoneguard").isEmpty()
        )
    }

    @Test
    fun systemAccessibilityServicesAreTrusted() {
        val raw = listOf(
            "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService",
            "com.google.android.accessibility.accessibilitymenu/.AccessibilityMenuService",
            "com.samsung.accessibility/com.samsung.accessibility.ScreenReaderService",
            "com.oneplus.switchaccess/.SwitchAccessService"
        ).joinToString(":")

        assertTrue(DeviceHeuristics.untrustedAccessibilityPackages(raw, "com.phoneguard").isEmpty())
    }

    @Test
    fun accessibilitySettingIsParsedIntoPackageNames() {
        val raw = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService:" +
            "com.evil.spy/com.evil.spy.Service:com.evil.spy/com.evil.spy.Other"

        // Раньше разбор шёл через split("/") и возвращал мусор вида
        // «com.pkg.Class:com.other.pkg».
        assertEquals(
            listOf("com.google.android.marvin.talkback", "com.evil.spy"),
            DeviceHeuristics.accessibilityPackages(raw)
        )
    }

    @Test
    fun blankSettingYieldsNothing() {
        assertTrue(DeviceHeuristics.untrustedAccessibilityPackages(null, "com.phoneguard").isEmpty())
        assertTrue(DeviceHeuristics.untrustedAccessibilityPackages("", "com.phoneguard").isEmpty())
        assertTrue(DeviceHeuristics.untrustedAccessibilityPackages("::", "com.phoneguard").isEmpty())
    }
}
