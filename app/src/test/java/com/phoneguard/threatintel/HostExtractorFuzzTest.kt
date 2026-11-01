package com.phoneguard.threatintel

import com.phoneguard.fullscan.checks.PhishingHeuristics
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Устойчивость разбора ссылок и текстов SMS.
 *
 * Оба разбора работают с данными, которые присылает кто угодно: тело SMS, строки фида, ярлыки
 * приложений. Поэтому проверяются не конкретные значения, а инварианты — что функция не падает,
 * не возвращает «полуразобранный» хост и не уходит в квадратичный разбор на мусоре
 * (сообщение приходит извне, и «подвесить» проверку — тоже атака).
 */
class HostExtractorFuzzTest {

    private val alphabet = "abcABC012.-_:@?#[]/\\%\"',;)><= \tая\u0000\uD83D\uDE00"

    private fun randomText(random: Random, maxLength: Int): String =
        (0 until random.nextInt(0, maxLength))
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")

    @Test
    fun `extractHost never throws and never returns a malformed host`() {
        val random = Random(20260101)
        repeat(3000) {
            val source = randomText(random, 40)
            val host = runCatching { HostExtractor.extractHost(source) }.getOrElse { error ->
                throw AssertionError("исключение на входе «$source»: $error")
            }
            if (host == null) return@repeat
            assertTrue("хост не в нижнем регистре: «$host» из «$source»", host == host.lowercase())
            assertTrue("пустой хост на входе «$source»", host.isNotEmpty())
            assertTrue("нет точки в «$host» (из «$source»)", '.' in host)
            assertFalse("схема осталась в «$host»", "://" in host)
            assertFalse("разделитель пути в «$host»", '/' in host || '?' in host || '#' in host)
            assertFalse("user-info в «$host»", '@' in host)
            assertFalse("порт в «$host»", ':' in host)
            assertTrue("крайняя точка в «$host»", host.first() != '.' && host.last() != '.')
            assertEquals("разбор не идемпотентен: «$host»", host, HostExtractor.extractHost(host))
        }
    }

    @Test
    fun `simple https links round-trip to their host`() {
        val random = Random(7)
        val tlds = listOf("com", "ru", "xyz", "de", "io")
        repeat(500) {
            val label = (1..random.nextInt(3, 12)).map { ('a' + random.nextInt(26)) }.joinToString("")
            val host = "$label.${tlds[random.nextInt(tlds.size)]}"
            assertEquals(host, HostExtractor.extractHost("https://$host/path?q=1"))
            assertEquals(host, HostExtractor.extractHost("http://$host"))
            assertNull(HostExtractor.extractHost("https://example.com/$host"))
        }
    }

    @Test
    fun `extractLinks never throws and links stay inside the text`() {
        val random = Random(2026)
        repeat(2000) {
            val body = randomText(random, 60)
            val links = runCatching { PhishingHeuristics.extractLinks(body) }.getOrElse { error ->
                throw AssertionError("исключение на тексте «$body»: $error")
            }
            links.forEach { link ->
                assertTrue("пустая ссылка в «$body»", link.isNotEmpty())
                assertTrue("ссылка «$link» не из текста", body.contains(link))
                assertFalse("пробел внутри ссылки «$link»", link.any { it.isWhitespace() })
                val last = link.last()
                assertFalse("ссылка «$link» кончается пунктуацией '$last'", last in ".,;:!?)]><\"'")
            }
            // isSuspicious — та же входная точка, что идёт из SMS.
            runCatching { PhishingHeuristics.isSuspicious(body) }.onFailure { error ->
                throw AssertionError("isSuspicious упал на «$body»: $error")
            }
        }
    }

    @Test
    fun `pathological dotted text does not take quadratic time`() {
        // Сообщение максимум ~1600 символов, но проверим с запасом: «a.a.a.a…» — худший случай
        // для регулярки с вложенным квантификатором. Порог щедрый, важно поймать не секунды, а
        // минуты: зависшая проверка на входящем SMS — это отказ в обслуживании.
        val body = "a.".repeat(2000) + "! http://evil.example/pay"
        val started = System.nanoTime()
        val links = PhishingHeuristics.extractLinks(body)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("разбор ${body.length} символов занял $elapsedMs мс", elapsedMs < 1000)
        assertTrue("ссылка http://evil.example/pay не найдена", links.any { it.startsWith("http://evil.example/pay") })
        assertNotNull(links)
    }

    @Test
    fun `megabyte of separators is handled without blowing up`() {
        val body = "-_./".repeat(20_000)
        val started = System.nanoTime()
        PhishingHeuristics.extractLinks(body)
        HostExtractor.extractHost(body)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("обработка ${body.length} символов заняла $elapsedMs мс", elapsedMs < 2000)
    }
}
