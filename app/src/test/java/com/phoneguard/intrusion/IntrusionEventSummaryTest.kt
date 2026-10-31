package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `summary()` попадает в журнал вторжений и в уведомление, поэтому формат должен быть
 * стабильным: тип, важность и улики в предсказуемом порядке (иначе тесты и grep по логам
 * разваливаются, а пользователь видит «шум» вместо причины).
 */
class IntrusionEventSummaryTest {

    private fun event(
        type: IntrusionType = IntrusionType.BRUTE_FORCE,
        severity: IntrusionSeverity = IntrusionSeverity.MEDIUM,
        evidence: Map<String, String> = emptyMap(),
    ) = IntrusionEvent(type = type, severity = severity, evidence = evidence)

    @Test
    fun `summary starts with type and severity`() {
        val text = event().summary()
        assertEquals("BRUTE_FORCE MEDIUM", text)
    }

    @Test
    fun `summary appends evidence as key equals value`() {
        val text = event(evidence = mapOf("attempts" to "5")).summary()
        assertEquals("BRUTE_FORCE MEDIUM attempts=5", text)
    }

    @Test
    fun `summary keeps insertion order of evidence`() {
        val evidence = linkedMapOf("package" to "com.evil", "uid" to "10123", "count" to "3")
        assertEquals(
            "RAT_DETECTED CRITICAL package=com.evil uid=10123 count=3",
            event(IntrusionType.RAT_DETECTED, IntrusionSeverity.CRITICAL, evidence).summary()
        )
    }

    @Test
    fun `summary has no trailing separator without evidence`() {
        assertTrue(!event().summary().endsWith(" "))
    }

    @Test
    fun `summary keeps single entry without separators`() {
        assertEquals("HONEYPOT_ACCESSED LOW path=/sdcard/bait.txt",
            event(IntrusionType.HONEYPOT_ACCESSED, IntrusionSeverity.LOW, mapOf("path" to "/sdcard/bait.txt")).summary())
    }

    @Test
    fun `event keeps the given severity`() {
        assertEquals(
            IntrusionSeverity.HIGH,
            event(IntrusionType.HOOKING_DETECTED, IntrusionSeverity.HIGH).severity,
        )
    }

    @Test
    fun `signals default to empty evidence and medium severity`() {
        val signal = IntrusionSignal(type = IntrusionType.FIREWALL_BYPASS)
        assertTrue(signal.evidence.isEmpty())
        assertEquals(IntrusionSeverity.MEDIUM, signal.severity)
    }

    @Test
    fun `unknown type is available as a fallback value`() {
        // Конвертер Room опирается на это значение при чтении старых/повреждённых строк.
        assertEquals(IntrusionType.UNKNOWN, IntrusionType.valueOf("UNKNOWN"))
    }
}
