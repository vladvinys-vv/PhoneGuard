package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

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
    fun separatorsInNamesDoNotHideAMatch() {
        // Регресс: шаблоны `chat.head` и `pop-up` сравнивались как подстроки с точкой/дефисом,
        // поэтому `com.x.chathead` (самая частая форма имени пакета) считался безопасным.
        assertEquals("chat.head", OverlayAttackCheck.matchingPattern("com.x.chathead", "ChatHead"))
        assertEquals("chat.head", OverlayAttackCheck.matchingPattern("com.x.chat_head", "Chat"))
        assertEquals("chat.head", OverlayAttackCheck.matchingPattern("com.x.other", "Chat Head"))
        assertEquals("pop-up", OverlayAttackCheck.matchingPattern("com.x.popupnotes", "Popup Notes"))
        assertEquals("pop-up", OverlayAttackCheck.matchingPattern("com.x.pop_up", "Notes"))
        assertEquals("screen.dim", OverlayAttackCheck.matchingPattern("com.x.screendim", "Night"))
        assertEquals("screen.filter", OverlayAttackCheck.matchingPattern("com.x.other", "Screen Filter"))
    }

    @Test
    fun appLabelMatchingIsCaseInsensitive() {
        assertEquals("floating", OverlayAttackCheck.matchingPattern("com.example.player", "Floating Player"))
    }

    @Test
    fun matchingDoesNotDependOnSystemLocale() {
        // В турецкой локали `I` превращается в `ı`: без Locale.ROOT шаблон «dimmer»
        // не совпадал с ярлыком «DIMMER», и оверлей-атака проходила проверку.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("dimmer", OverlayAttackCheck.matchingPattern("com.example.dimmer", "DIMMER"))
            assertEquals("dimmer", OverlayAttackCheck.matchingPattern("com.example.other", "Dimmer"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun ordinaryAppsAreNotMatched() {
        assertNull(OverlayAttackCheck.matchingPattern("com.example.bank", "Bank"))
        assertNull(OverlayAttackCheck.matchingPattern("com.example.fridays", "Fridays"))
        assertNull(OverlayAttackCheck.matchingPattern("org.wikipedia", "Wikipedia"))
    }
}
