package com.phoneguard.threatintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вердикты threat-intel: какие из них блокируют соединение и что попадает в «неизвестно».
 *
 * Ошибка здесь дорогая в обе стороны: если [ThreatVerdict.UNKNOWN] начнёт считаться блокирующим,
 * приложение заблокирует весь трафик при недоступном источнике; если [ThreatVerdict.SUSPICIOUS]
 * перестанет им быть — фишинговый домен пройдёт.
 */
class ThreatIntelModelsTest {

    @Test
    fun `only suspicious and malicious verdicts block`() {
        assertTrue(ThreatVerdict.MALICIOUS.isBlocking)
        assertTrue(ThreatVerdict.SUSPICIOUS.isBlocking)
        assertFalse(ThreatVerdict.CLEAN.isBlocking)
        assertFalse(ThreatVerdict.UNKNOWN.isBlocking)
    }

    @Test
    fun `unknown result carries host and defaults`() {
        val result = ThreatIntelResult.unknown("example.org")

        assertEquals("example.org", result.host)
        assertEquals(ThreatVerdict.UNKNOWN, result.verdict)
        assertEquals("none", result.source)
        assertNull(result.details)
        assertFalse(result.verdict.isBlocking)
    }

    @Test
    fun `unknown result accepts explicit source and details`() {
        val result = ThreatIntelResult.unknown("evil.test", source = "urlhaus.abuse.ch", details = "timeout")

        assertEquals("urlhaus.abuse.ch", result.source)
        assertEquals("timeout", result.details)
        assertEquals(ThreatVerdict.UNKNOWN, result.verdict)
    }

    @Test
    fun `result keeps verdict source and host`() {
        val result =
            ThreatIntelResult(host = "phish.test", verdict = ThreatVerdict.SUSPICIOUS, source = ThreatIntelSources.OPENPHISH)

        assertEquals("phish.test", result.host)
        assertEquals(ThreatIntelSources.OPENPHISH, result.source)
        assertTrue(result.verdict.isBlocking)
    }
}
