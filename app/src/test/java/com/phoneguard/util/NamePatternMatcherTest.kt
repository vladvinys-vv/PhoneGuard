package com.phoneguard.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Сравнение имён с подозрительными шаблонами.
 *
 * Здесь закреплены обе стороны ошибки, из-за которой проверки расходились между собой:
 * пропуск («anti.theft» не находил ярлык «ANTI-THEFT») и ложная тревога («admin» находился
 * в «badminton», «lock» — в «blockchain»).
 */
class NamePatternMatcherTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun tokensSplitOnSeparatorsAndCamelCase() {
        assertEquals(listOf("com", "example", "anti", "theft"), NamePatternMatcher.tokens("com.example.anti_theft"))
        assertEquals(listOf("chat", "head"), NamePatternMatcher.tokens("ChatHead"))
        assertEquals(listOf("m", "spy"), NamePatternMatcher.tokens("mSpy"))
        assertEquals(listOf("screen", "filter", "pro"), NamePatternMatcher.tokens("Screen-Filter Pro"))
        assertEquals(emptyList<String>(), NamePatternMatcher.tokens(""))
        assertEquals(emptyList<String>(), NamePatternMatcher.tokens("._- "))
    }

    @Test
    fun separatedPatternMatchesJoinedAndSeparatedNames() {
        // Бывший пропуск: шаблон записан точкой, а название — дефисом, пробелом или слитно.
        assertTrue(NamePatternMatcher.matches("ANTI-THEFT", "anti.theft"))
        assertTrue(NamePatternMatcher.matches("Anti Theft", "anti.theft"))
        assertTrue(NamePatternMatcher.matches("AntiTheft", "anti.theft"))
        assertTrue(NamePatternMatcher.matches("com.x.antitheft", "anti.theft"))
        assertTrue(NamePatternMatcher.matches("com.x.popupnotes", "pop-up"))
        assertTrue(NamePatternMatcher.matches("com.x.chathead", "chat.head"))
        assertTrue(NamePatternMatcher.matches("AutoClicker", "auto.click"))
    }

    @Test
    fun shortPatternDoesNotMatchInsideAnotherWord() {
        // Бывшие ложные тревоги: короткие общие слова искались подстрокой.
        assertFalse(NamePatternMatcher.matches("com.example.badminton", "admin"))
        assertFalse(NamePatternMatcher.matches("com.wallet.blockchain", "lock"))
        assertFalse(NamePatternMatcher.matches("com.example.clocks", "lock"))
        assertFalse(NamePatternMatcher.matches("com.example.avengers", "eng"))
        assertFalse(NamePatternMatcher.matches("org.wikipedia", "wipe"))
    }

    @Test
    fun shortPatternMatchesAtStartOfWord() {
        assertTrue(NamePatternMatcher.matches("com.evil.adminservice", "admin"))
        assertTrue(NamePatternMatcher.matches("App Lock", "lock"))
        assertTrue(NamePatternMatcher.matches("Locker Pro", "lock"))
        assertTrue(NamePatternMatcher.matches("com.spy.tool", "spy"))
        assertTrue(NamePatternMatcher.matches("mSpy", "spy"))
    }

    @Test
    fun longPatternMatchesInsideWord() {
        // Длинные специфичные шаблоны внутри слова — это по-прежнему находка:
        // иначе терялись бы «keylogger», «screenshotter», «antithefttool».
        assertTrue(NamePatternMatcher.matches("keylogger", "keylog"))
        assertTrue(NamePatternMatcher.matches("com.x.screenshotter", "screenshot"))
        assertTrue(NamePatternMatcher.matches("myantithefttool", "anti.theft"))
    }

    @Test
    fun digitsDoNotBreakWordStart() {
        // Цифру в название вставляют в любое место: «spy2go», «screen2filter».
        assertEquals(listOf("screen", "2", "filter"), NamePatternMatcher.tokens("screen2filter"))
        assertTrue(NamePatternMatcher.matches("spy2go", "spy"))
        assertTrue(NamePatternMatcher.matches("screen2filter", "screen.filter"))
        assertTrue(NamePatternMatcher.matches("com.x.lock2app", "lock"))
    }

    @Test
    fun emptyInputsAreNotMatches() {
        assertFalse(NamePatternMatcher.matches("", "spy"))
        assertFalse(NamePatternMatcher.matches("com.example.tool", ""))
        assertFalse(NamePatternMatcher.matches("com.example.tool", "._-"))
    }

    @Test
    fun matchingIsIndependentOfDefaultLocale() {
        // В турецкой локали `I` превращается в `ı`; сравнение обязано этого не замечать.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertTrue(NamePatternMatcher.matches("DIMMER", "dimmer"))
        assertTrue(NamePatternMatcher.matches("SCREEN FILTER", "screen.filter"))
        assertFalse(NamePatternMatcher.matches("badminton", "admin"))
    }

    @Test
    fun firstMatchReturnsPatternInListOrder() {
        val patterns = listOf("autoclick", "auto.click", "spy")
        // Порядок важен: в отчёт попадает первый совпавший шаблон, а не самый точный.
        assertEquals("autoclick", NamePatternMatcher.firstMatch("com.example.autoclicker", "Clicker", patterns))
        assertEquals("spy", NamePatternMatcher.firstMatch("com.example.spy", null, patterns))
        assertNull(NamePatternMatcher.firstMatch("com.example.tool", "Calculator", patterns))
    }

    @Test
    fun nullLabelChecksPackageOnly() {
        val patterns = listOf("spy")
        assertTrue(NamePatternMatcher.firstMatch("com.example.spy", null, patterns) == "spy")
        assertNull(NamePatternMatcher.firstMatch("com.example.tool", null, patterns))
    }

    @Test
    fun labelIsUsedWhenPackageIsClean() {
        val patterns = listOf("keylog")
        assertEquals("keylog", NamePatternMatcher.firstMatch("com.example.helper", "KeyLog Pro", patterns))
    }

    @Test
    fun emptyPatternListNeverMatches() {
        assertNull(NamePatternMatcher.firstMatch("com.example.spy", "Spy", emptyList()))
    }
}
