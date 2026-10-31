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
    fun `honeypot identifies decoy access`() {
        val manager = HoneypotManager()
        assertTrue(manager.isDecoyAccess("/data/passwords.txt"))
    }
}
