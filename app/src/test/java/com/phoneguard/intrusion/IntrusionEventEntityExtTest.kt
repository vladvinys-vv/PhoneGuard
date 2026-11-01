package com.phoneguard.intrusion

import com.phoneguard.data.local.IntrusionEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Переход «сущность Room ↔ событие» и разбор поля улик.
 *
 * Улики хранятся строкой `ключ=значение;ключ=значение`. Без экранирования значение с `;` или `=`
 * разваливало запись: `path=/sdcard/a;b.jpg` читалось как две улики, а `rule=a=b` теряло хвост.
 * Строки, записанные раньше, экранирования не содержат и должны разбираться по-старому.
 */
class IntrusionEventEntityExtTest {

    private fun entity(evidence: String) = IntrusionEventEntity(
        id = 7,
        timestamp = 42,
        type = IntrusionType.RAT_DETECTED,
        severity = IntrusionSeverity.HIGH,
        evidence = evidence,
        uid = 10,
        packageName = "evil",
        ipAddress = "1.2.3.4",
        photoPath = "/tmp/p.jpg",
        handled = true
    )

    private fun event(evidence: Map<String, String>) = IntrusionEvent(
        id = 7,
        timestamp = 42,
        type = IntrusionType.RAT_DETECTED,
        severity = IntrusionSeverity.HIGH,
        evidence = evidence,
        uid = 10,
        packageName = "evil",
        ipAddress = "1.2.3.4",
        photoPath = "/tmp/p.jpg",
        handled = true
    )

    @Test
    fun `round trips event`() {
        val event = entity("a=1;b=2").toIntrusionEvent()
        assertEquals(IntrusionType.RAT_DETECTED, event.type)
        assertEquals(IntrusionSeverity.HIGH, event.severity)
        assertEquals("evil", event.packageName)
        assertEquals("/tmp/p.jpg", event.photoPath)
        assertEquals(10, event.uid)
        assertEquals("1.2.3.4", event.ipAddress)
        assertTrue(event.handled)
        assertEquals(mapOf("a" to "1", "b" to "2"), event.evidence)
    }

    @Test
    fun `old rows without escaping are still parsed`() {
        // Формат до экранирования: обычное значение и пара без '='.
        val parsed = parseEvidence("package=com.evil;flag;count=3")
        assertEquals("com.evil", parsed["package"])
        assertEquals("", parsed["flag"])
        assertEquals("3", parsed["count"])
    }

    @Test
    fun `empty evidence string gives empty map`() {
        assertTrue(parseEvidence("").isEmpty())
        assertTrue(parseEvidence("   ").isEmpty())
    }

    @Test
    fun `blank segments are skipped`() {
        assertEquals(mapOf("a" to "1", "b" to "2"), parseEvidence("a=1;;b=2;"))
    }

    @Test
    fun `value containing assignment sign is preserved`() {
        assertEquals(mapOf("rule" to "a=b=c"), parseEvidence("rule=a=b=c"))
    }

    @Test
    fun `value containing separator survives a database round trip`() {
        val original = event(mapOf("path" to "/sdcard/a;b.jpg"))
        val restored = original.toEntity().toIntrusionEvent()
        assertEquals("/sdcard/a;b.jpg", restored.evidence["path"])
        assertEquals(original.evidence, restored.evidence)
    }

    @Test
    fun `value containing assignment sign survives a database round trip`() {
        val original = event(mapOf("rule" to "tcp=blocked"))
        assertEquals(original.evidence, original.toEntity().toIntrusionEvent().evidence)
    }

    @Test
    fun `key containing separators survives a database round trip`() {
        val original = event(mapOf("a;b" to "1", "c=d" to "2"))
        assertEquals(original.evidence, original.toEntity().toIntrusionEvent().evidence)
    }

    @Test
    fun `backslash is escaped and restored`() {
        val original = event(mapOf("path" to "C:\\temp\\a.txt"))
        assertEquals("C:\\temp\\a.txt", original.toEntity().toIntrusionEvent().evidence["path"])
    }

    @Test
    fun `full event round trip keeps every field`() {
        val original = event(mapOf("a" to "1", "b" to "x;y=z"))
        assertEquals(original, original.toEntity().toIntrusionEvent())
    }

    @Test
    fun `evidence order is preserved`() {
        val parsed = parseEvidence("z=1;a=2;m=3")
        assertEquals(listOf("z", "a", "m"), parsed.keys.toList())
    }

    @Test
    fun `escape and unescape are inverse`() {
        listOf("plain", "a;b", "a=b", "a\\b", ";=;", "").forEach { text ->
            assertEquals(text, unescapeEvidence(escapeEvidence(text)))
        }
    }

    @Test
    fun `broken escaping at the end of a row does not throw`() {
        // Повреждённая строка: экранирование оборвано на конце — читаем как обычный символ.
        assertEquals(mapOf("src" to "a\\"), parseEvidence("src=a\\"))
        assertEquals(mapOf("path" to ""), parseEvidence("path="))
    }
}
