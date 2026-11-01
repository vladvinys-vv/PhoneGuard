package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Признаки «перехватчика экрана» среди служб доступности.
 */
class AccessibilityHeuristicsTest {

    @Test
    fun autoClickerNameIsDetected() {
        assertEquals(
            "autoclick",
            AccessibilityHeuristics.matchingPattern("com.example.autoclicker", "Clicker")
        )
    }

    @Test
    fun dottedKeywordsAreDetected() {
        assertEquals(
            "auto.click",
            AccessibilityHeuristics.matchingPattern("com.example.auto.click.pro", null)
        )
        assertEquals(
            "accessibility.click",
            AccessibilityHeuristics.matchingPattern("org.accessibility.clicker", null)
        )
    }

    @Test
    fun appLabelIsAlsoChecked() {
        assertEquals(
            "keylog",
            AccessibilityHeuristics.matchingPattern("com.example.helper", "KeyLog Pro")
        )
    }

    @Test
    fun removedAppIsCheckedByPackageOnly() {
        assertEquals("spy", AccessibilityHeuristics.matchingPattern("com.spy.tool", null))
        assertNull(AccessibilityHeuristics.matchingPattern("com.example.tool", null))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals("screenshot", AccessibilityHeuristics.matchingPattern("com.example.SCREENSHOT", null))
        assertEquals("tracker", AccessibilityHeuristics.matchingPattern("com.example.x", "TRACKER"))
    }

    @Test
    fun doesNotDependOnSystemLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Турецкая локаль: результат сравнения не должен от неё зависеть.
            assertEquals("monitor", AccessibilityHeuristics.matchingPattern("com.example.MONITOR", null))
            assertEquals("screenshot", AccessibilityHeuristics.matchingPattern("com.x", "SCREENSHOT"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun ordinaryServicesAreNotMatched() {
        assertNull(AccessibilityHeuristics.matchingPattern("com.google.android.marvin.talkback", "TalkBack"))
        assertNull(AccessibilityHeuristics.matchingPattern("com.example.reader", null))
    }
}
