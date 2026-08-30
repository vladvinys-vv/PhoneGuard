package com.phoneguard.fullscan.checks

import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class PhishingUrlCheckerTest {

    private val checker = PhishingUrlChecker(mockk(relaxed = true))

    @Test
    fun `isSuspicious returns true for phishing message with link and keywords`() {
        val body = "Перейдите по ссылке bit.ly/abc и получите выигрыш от банка"
        assertTrue(checker.isSuspicious(body))
    }

    @Test
    fun `isSuspicious returns false for message without keywords`() {
        val body = "Привет, как дела? https://example.com"
        assertFalse(checker.isSuspicious(body))
    }

    @Test
    fun `isSuspicious returns false for message without link`() {
        val body = "Перейдите и получите выигрыш от банка"
        assertFalse(checker.isSuspicious(body))
    }

    @Test
    fun `isSuspicious returns true for t.me link with urgent keyword`() {
        val body = "Срочно подтвердите код по ссылке t.me/channel"
        assertTrue(checker.isSuspicious(body))
    }
}
