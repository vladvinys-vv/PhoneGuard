package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Признаки «подозрительного имени» для оверлеев. Проверка разрешения
 * `SYSTEM_ALERT_WINDOW` живёт в самом чеке (нужен `PackageManager`), а сравнение
 * имён — чистая функция, поэтому покрывается здесь.
 */
class OverlayAttackCheckTest {

    @Test
    fun suspiciousPackageNamesAreMatched() {
        assertEquals("screen.filter", OverlayAttackCheck.matchingPattern("com.example.screen.filter", "Filter"))
        assertEquals("bubble", OverlayAttackCheck.matchingPattern("com.example.bubblechat", "Bubble"))
        assertEquals("overlay", OverlayAttackCheck.matchingPattern("com.x.overlaypro", "Overlay Pro"))
        assertEquals("chat.head", OverlayAttackCheck.matchingPattern("com.x.chathead", "ChatHead"))
    }

    @Test
    fun appLabelMatchingIsCaseInsensitive() {
        assertEquals("floating", OverlayAttackCheck.matchingPattern("com.example.player", "Floating Player"))
    }

    @Test
    fun ordinaryAppsAreNotMatched() {
        assertNull(OverlayAttackCheck.matchingPattern("com.example.bank", "Bank"))
        assertNull(OverlayAttackCheck.matchingPattern("com.example.fridays", "Fridays"))
        assertNull(OverlayAttackCheck.matchingPattern("org.wikipedia", "Wikipedia"))
    }
}
