package com.phoneguard.util

import org.junit.Assert.*
import org.junit.Test

class SecurityUtilsTest {

    @Test
    fun `hashPin generates consistent hash`() {
        val hash1 = SecurityUtils.hashPin("1234")
        val hash2 = SecurityUtils.hashPin("1234")
        assertNotEquals(hash1, hash2) // salts differ
        assertTrue(hash1.contains(":"))
        assertTrue(hash2.contains(":"))
    }

    @Test
    fun `verifyPin returns true for correct pin`() {
        val hash = SecurityUtils.hashPin("1234")
        assertTrue(SecurityUtils.verifyPin("1234", hash))
    }

    @Test
    fun `verifyPin returns false for wrong pin`() {
        val hash = SecurityUtils.hashPin("1234")
        assertFalse(SecurityUtils.verifyPin("5678", hash))
    }

    @Test
    fun `verifyPin returns false for null hash`() {
        assertFalse(SecurityUtils.verifyPin("1234", null))
    }

    @Test
    fun `verifyPin returns false for malformed hash`() {
        assertFalse(SecurityUtils.verifyPin("1234", "invalid"))
        assertFalse(SecurityUtils.verifyPin("1234", "a:b:c"))
    }
}
