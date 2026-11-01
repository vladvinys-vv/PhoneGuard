package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntrusionDetectorTest {
    @Test
    fun `detect converts signals to events`() {
        val detector = IntrusionDetector()
        val events = detector.detect(
            listOf(
                IntrusionSignal(IntrusionType.RAT_DETECTED, mapOf("proc" to "evil"), IntrusionSeverity.HIGH)
            )
        )
        assertEquals(1, events.size)
        assertEquals(IntrusionType.RAT_DETECTED, events.first().type)
        assertEquals(IntrusionSeverity.HIGH, events.first().severity)
    }

    @Test
    fun `detect keeps order and evidence of every signal`() {
        val detector = IntrusionDetector()
        val signals = listOf(
            IntrusionSignal(IntrusionType.BRUTE_FORCE, mapOf("attempts" to "5"), IntrusionSeverity.MEDIUM),
            IntrusionSignal(IntrusionType.HONEYPOT_ACCESSED, mapOf("file" to "/sdcard/x"), IntrusionSeverity.HIGH),
            IntrusionSignal(IntrusionType.UNKNOWN, emptyMap(), IntrusionSeverity.LOW)
        )

        val events = detector.detect(signals)

        assertEquals(3, events.size)
        assertEquals(signals.map { it.type }, events.map { it.type })
        assertEquals(signals.map { it.severity }, events.map { it.severity })
        assertEquals(signals.map { it.evidence }, events.map { it.evidence })
        assertTrue(events.first().evidence["attempts"] == "5")
    }

    @Test
    fun `detect on empty list stays empty`() {
        assertTrue(IntrusionDetector().detect(emptyList()).isEmpty())
    }
}
