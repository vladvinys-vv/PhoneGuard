package com.phoneguard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternRulesTest {

    @Test
    fun `matchesPattern returns false for blank pattern`() {
        assertFalse(PatternRules.matchesPattern("+79991234567", ""))
        assertFalse(PatternRules.matchesPattern("+79991234567", "   "))
    }

    @Test
    fun `matchesPattern matches exact number`() {
        assertTrue(PatternRules.matchesPattern("+79991234567", "+79991234567"))
    }

    @Test
    fun `matchesPattern matches wildcard`() {
        assertTrue(PatternRules.matchesPattern("+79991234567", "+7*"))
        assertTrue(PatternRules.matchesPattern("+79991234567", "*34567"))
    }

    @Test
    fun `matchesPattern matches single character wildcard`() {
        assertTrue(PatternRules.matchesPattern("+79991234567", "+7999123456?"))
    }

    @Test
    fun `matchesPattern returns false for non-matching pattern`() {
        assertFalse(PatternRules.matchesPattern("+79991234567", "+7888*"))
    }

    @Test
    fun `validatePattern returns true for valid pattern`() {
        assertTrue(PatternRules.validatePattern("+7*"))
        assertTrue(PatternRules.validatePattern("*34567"))
    }

    @Test
    fun `regex metacharacters in pattern are treated literally`() {
        // Скобки — часть записи номера, а не группа регулярного выражения.
        assertTrue(PatternRules.matchesPattern("+49 (170) 1234567", "+49 (170) 1234567"))
        assertFalse(PatternRules.matchesPattern("49170", "+49 (170)"))
        // `|` не должен превращаться в «или».
        assertFalse(PatternRules.matchesPattern("+491701234567", "+7|+49"))
        assertTrue(PatternRules.matchesPattern("+7|+49", "+7|+49"))
    }

    @Test
    fun `wildcards still work next to literal characters`() {
        assertTrue(PatternRules.matchesPattern("+49 (170) 1234567", "+49 (170)*"))
        assertFalse(PatternRules.matchesPattern("+49 (171) 1234567", "+49 (170)*"))
    }

    @Test
    fun `validatePattern returns false for invalid pattern`() {
        assertFalse(PatternRules.validatePattern(""))
        assertFalse(PatternRules.validatePattern("   "))
    }
}
