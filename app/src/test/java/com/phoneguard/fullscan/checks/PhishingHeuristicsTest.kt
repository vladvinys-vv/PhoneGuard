package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Эвристика фишинга в SMS.
 *
 * Цена ошибки несимметрична: пропущенное сообщение (false negative) — пользователь идёт по ссылке
 * мошенников; лишняя находка (false positive) — неприятный, но безвредный пункт в отчёте. Поэтому
 * тесты фиксируют и то, что ловится, и то, что ловиться **не** должно.
 */
class PhishingHeuristicsTest {

    @Test
    fun `link with pressing keyword is suspicious`() {
        assertTrue(PhishingHeuristics.isSuspicious("Перейдите по ссылке bit.ly/abc и получите выигрыш"))
        assertTrue(PhishingHeuristics.isSuspicious("Срочно подтвердите код по ссылке t.me/channel"))
    }

    @Test
    fun `keyword alone is not enough`() {
        assertFalse(PhishingHeuristics.isSuspicious("Перейдите и получите выигрыш от банка"))
    }

    @Test
    fun `link alone is not enough`() {
        assertFalse(PhishingHeuristics.isSuspicious("Привет, как дела? https://example.com"))
    }

    @Test
    fun `german phishing message is detected`() {
        assertTrue(
            PhishingHeuristics.isSuspicious(
                "Ihr Konto wurde gesperrt. Bitte bestätigen Sie: https://sparkasse-sicherheit.de"
            )
        )
    }

    @Test
    fun `bare domain in risky zone is a link`() {
        // Регресс: раньше голый домен без схемы считался ссылкой только при наличии `/`
        // (`.ru/`, `.com/`), поэтому «sberbank-online.ru» в сообщении не находился вообще.
        assertTrue(PhishingHeuristics.isSuspicious("Ваша карта заблокирована, подробности: sberbank-online.ru"))
        assertTrue(PhishingHeuristics.isSuspicious("Срочно: paypal.com-konto.xyz"))
        assertEquals(listOf("sberbank-online.ru"), PhishingHeuristics.extractLinks("зайдите на sberbank-online.ru сейчас"))
    }

    @Test
    fun `extractLinks returns every link and trims punctuation`() {
        val body = "Смотри https://a.example/path! и www.b.example, а также t.me/channel."
        assertEquals(
            listOf("https://a.example/path", "www.b.example", "t.me/channel"),
            PhishingHeuristics.extractLinks(body)
        )
    }

    @Test
    fun `extractLinks ignores uppercase schemes and keeps original case`() {
        assertEquals(
            listOf("HTTPS://Evil.Example/Track?x=1"),
            PhishingHeuristics.extractLinks("Жми HTTPS://Evil.Example/Track?x=1")
        )
    }

    @Test
    fun `text without links extracts nothing`() {
        assertTrue(PhishingHeuristics.extractLinks("Просто текст. И точка.").isEmpty())
        assertTrue(PhishingHeuristics.extractLinks("").isEmpty())
    }

    @Test
    fun `ordinary sentences do not become links`() {
        // Аббревиатуры и «сокращения» без домена не должны превращаться в ссылки.
        assertTrue(PhishingHeuristics.extractLinks("т.е. это не ссылка").isEmpty())
        assertTrue(PhishingHeuristics.extractLinks("Цена 10.50 EUR за литр").isEmpty())
    }
}
