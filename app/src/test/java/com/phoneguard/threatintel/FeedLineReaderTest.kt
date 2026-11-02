package com.phoneguard.threatintel

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение публичного фида с границами по памяти.
 *
 * Раньше клиент ограничивал только число строк, поэтому ответ без переводов строк
 * читался бы одним куском и мог уронить приложение по OOM.
 */
class FeedLineReaderTest {

    @Test
    fun `строки читаются без переводов строк`() {
        val result = FeedLineReader.read(StringReader("a.com\nb.com\nc.com"), maxLines = 100, maxChars = 1000)

        assertEquals(listOf("a.com", "b.com", "c.com"), result.lines)
        assertFalse(result.truncated)
    }

    @Test
    fun `CRLF не попадает в строки`() {
        val result = FeedLineReader.read(StringReader("a.com\r\nb.com\r\n"), maxLines = 100, maxChars = 1000)

        assertEquals(listOf("a.com", "b.com"), result.lines)
        assertFalse(result.truncated)
    }

    @Test
    fun `последняя строка без перевода строки не теряется`() {
        val result = FeedLineReader.read(StringReader("a.com\nb.com"), maxLines = 100, maxChars = 1000)

        assertEquals(listOf("a.com", "b.com"), result.lines)
    }

    @Test
    fun `пустой ответ даёт пустой список`() {
        val result = FeedLineReader.read(StringReader(""), maxLines = 100, maxChars = 1000)

        assertTrue(result.lines.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `число строк ограничено`() {
        val body = (1..50).joinToString("\n") { "$it.example.com" }
        val result = FeedLineReader.read(StringReader(body), maxLines = 10, maxChars = 100_000)

        assertEquals(10, result.lines.size)
        assertTrue("обрезка по строкам должна быть видна", result.truncated)
    }

    @Test
    fun `одна гигантская строка обрезается по символам`() {
        // Ответ без переводов строк — именно тот случай, который ронял приложение.
        val result = FeedLineReader.read(StringReader("x".repeat(10_000)), maxLines = 10, maxChars = 500)

        assertTrue("чтение должно остановиться на лимите", result.truncated)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `лимит символов не даёт превысить буфер строки`() {
        val body = "a".repeat(499) + "\nb"
        val result = FeedLineReader.read(StringReader(body), maxLines = 10, maxChars = 500)

        // 500-й символ — перевод строки, то есть лимит исчерпан ровно на границе.
        assertEquals(listOf("a".repeat(499)), result.lines)
        assertTrue(result.truncated)
    }
}
