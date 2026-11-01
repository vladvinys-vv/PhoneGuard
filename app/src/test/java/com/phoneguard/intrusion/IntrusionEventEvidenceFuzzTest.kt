package com.phoneguard.intrusion

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Улики интрузий переживают запись в базу и чтение обратно.
 *
 * Формат `ключ=значение;ключ=значение` с экранированием — самое хрупкое место: разделители
 * приходят из пути файла (`/sdcard/a;b.jpg`), из правила (`rule=a=b`), из имени пакета. Раньше
 * такая улика разваливалась на две, и в журнале оказывался мусор вместо события. Тесты ниже
 * проверяют не отдельные примеры, а инвариант: **любая** карта улик возвращается без потерь.
 */
class IntrusionEventEvidenceFuzzTest {

    /** Алфавит намеренно включает разделители формата, экранирующий символ и не-ASCII. */
    private val alphabet = "ab Z09\\;=,.\"'|/:\n\t\u0000ёЁ😀-%"

    private fun randomText(random: Random, maxLength: Int = 12): String =
        (0 until random.nextInt(0, maxLength + 1))
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")

    private fun randomEvidence(random: Random): Map<String, String> =
        (0 until random.nextInt(0, 5)).associate { randomText(random) to randomText(random) }

    @Test
    fun `evidence round-trips through entity for arbitrary strings`() {
        val random = Random(4207)
        repeat(1500) {
            val evidence = randomEvidence(random)
            val event = IntrusionEvent(
                id = 1,
                timestamp = 1_700_000_000_000,
                type = IntrusionType.RAT_DETECTED,
                severity = IntrusionSeverity.HIGH,
                evidence = evidence
            )

            val restored = event.toEntity().toIntrusionEvent()

            assertEquals("улики потерялись: было $evidence", evidence, restored.evidence)
            assertEquals(event.type, restored.type)
            assertEquals(event.severity, restored.severity)
            assertEquals(event.timestamp, restored.timestamp)
        }
    }

    @Test
    fun `values with format separators survive`() {
        val nasty = mapOf(
            "path" to "/sdcard/a;b.jpg",
            "rule" to "a=b",
            "both" to "x=y;z",
            "escape" to "\\",
            "trailing" to "end\\",
            "unicode" to "файл 😀",
            "newline" to "a\nb",
            "empty" to ""
        )
        val restored = IntrusionEvent(
            type = IntrusionType.HONEYPOT_ACCESSED,
            severity = IntrusionSeverity.HIGH,
            evidence = nasty
        ).toEntity().toIntrusionEvent()

        assertEquals(nasty, restored.evidence)
    }

    @Test
    fun `empty evidence stays empty`() {
        val restored = IntrusionEvent(
            type = IntrusionType.UNKNOWN,
            severity = IntrusionSeverity.LOW
        ).toEntity().toIntrusionEvent()

        assertTrue(restored.evidence.isEmpty())
    }

    @Test
    fun `legacy rows without escaping are still parsed`() {
        // Строки, записанные до появления экранирования, не содержат `\` — миграция не нужна.
        assertEquals(
            mapOf("proc" to "/system/bin/su", "pid" to "1234"),
            parseEvidence("proc=/system/bin/su;pid=1234")
        )
    }

    @Test
    fun `summary never throws and always starts with type and severity`() {
        val random = Random(99)
        repeat(1000) {
            val event = IntrusionEvent(
                type = IntrusionType.values().random(random),
                severity = IntrusionSeverity.values().random(random),
                evidence = randomEvidence(random)
            )
            val summary = runCatching { event.summary() }.getOrElse { error ->
                throw AssertionError("summary упал на ${event.evidence}: $error")
            }
            assertTrue("пустое описание", summary.isNotEmpty())
            assertTrue(
                "описание не начинается с типа и важности: «$summary»",
                summary.startsWith(event.type.name) && summary.contains(event.severity.name)
            )
        }
    }
}
