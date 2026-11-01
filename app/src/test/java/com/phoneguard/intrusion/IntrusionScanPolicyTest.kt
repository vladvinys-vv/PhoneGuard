package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сканирование окружения идёт на каждом старте приложения — повторные события не
 * должны ни копиться в истории, ни повторно запускать реакцию (тревогу, фото, SMS).
 */
class IntrusionScanPolicyTest {

    private fun event(
        type: IntrusionType = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
        evidence: Map<String, String> = mapOf("reason" to "adb_enabled")
    ) = IntrusionEvent(type = type, severity = IntrusionSeverity.MEDIUM, evidence = evidence)

    @Test
    fun firstScanKeepsEverything() {
        val detected = listOf(event(), event(type = IntrusionType.RAT_DETECTED, evidence = mapOf("reason" to "root_binary_present")))
        assertEquals(detected, IntrusionScanPolicy.selectNewEvents(detected, emptyList()))
    }

    @Test
    fun alreadyRecordedSignalIsSkipped() {
        val known = event()
        val new = event(evidence = mapOf("reason" to "debuggable_build"))
        val selected = IntrusionScanPolicy.selectNewEvents(listOf(known, new), listOf(known))
        assertEquals(listOf(new), selected)
    }

    @Test
    fun sameTypeWithDifferentEvidenceIsANewSignal() {
        val old = event(evidence = mapOf("services" to "com.evil.one"))
        val fresh = event(evidence = mapOf("services" to "com.evil.two"))
        assertEquals(listOf(fresh), IntrusionScanPolicy.selectNewEvents(listOf(fresh), listOf(old)))
    }

    @Test
    fun duplicatesInsideOneScanAreCollapsed() {
        val detected = listOf(event(), event(), event(type = IntrusionType.RAT_DETECTED, evidence = mapOf("reason" to "x")))
        val selected = IntrusionScanPolicy.selectNewEvents(detected, emptyList())
        assertEquals(2, selected.size)
        assertTrue(selected.all { it.type != IntrusionType.UNKNOWN })
    }

    @Test
    fun sameTypeWithDifferentEvidenceIsNew() {
        val detected = listOf(
            IntrusionEvent(type = IntrusionType.FIREWALL_BYPASS, severity = IntrusionSeverity.HIGH, evidence = mapOf("ip" to "1.1.1.1")),
            IntrusionEvent(type = IntrusionType.FIREWALL_BYPASS, severity = IntrusionSeverity.HIGH, evidence = mapOf("ip" to "2.2.2.2"))
        )
        val recent = listOf(
            IntrusionEvent(type = IntrusionType.FIREWALL_BYPASS, severity = IntrusionSeverity.HIGH, evidence = mapOf("ip" to "1.1.1.1"))
        )
        val fresh = IntrusionScanPolicy.selectNewEvents(detected, recent)
        assertEquals(1, fresh.size)
        assertEquals(mapOf("ip" to "2.2.2.2"), fresh.single().evidence)
    }

    @Test
    fun duplicatesInsideDetectedAreCollapsed() {
        val event = IntrusionEvent(type = IntrusionType.UNKNOWN, severity = IntrusionSeverity.MEDIUM)
        val fresh = IntrusionScanPolicy.selectNewEvents(listOf(event, event.copy(id = 99)), emptyList())
        assertEquals(1, fresh.size)
    }

    @Test
    fun emptyDetectedGivesNothing() {
        assertTrue(IntrusionScanPolicy.selectNewEvents(emptyList(), listOf(
            IntrusionEvent(type = IntrusionType.RAT_DETECTED, severity = IntrusionSeverity.HIGH)
        )).isEmpty())
    }

    @Test
    fun emptyRecentKeepsAllDetected() {
        val detected = listOf(
            IntrusionEvent(type = IntrusionType.RAT_DETECTED, severity = IntrusionSeverity.HIGH),
            IntrusionEvent(type = IntrusionType.HOOKING_DETECTED, severity = IntrusionSeverity.MEDIUM)
        )
        assertEquals(2, IntrusionScanPolicy.selectNewEvents(detected, emptyList()).size)
    }
}
