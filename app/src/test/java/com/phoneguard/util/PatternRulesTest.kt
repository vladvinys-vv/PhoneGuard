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
    fun `validatePattern returns false for invalid pattern`() {
        assertFalse(PatternRules.validatePattern(""))
        assertFalse(PatternRules.validatePattern("   "))
    }
}
