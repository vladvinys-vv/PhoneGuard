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
    fun `obfuscated dots are unmasked`() {
        // Так пишут половину фишинговых SMS именно для обхода фильтров: `[.]`, `(.)`, `{.}`.
        assertTrue(
            PhishingHeuristics.isSuspicious("Ваша карта заблокирована, детали: sberbank-online[.]ru")
        )
        assertEquals(
            listOf("sberbank-online.ru"),
            PhishingHeuristics.extractLinks("зайдите на sberbank-online[.]ru сейчас")
        )
        assertEquals(
            listOf("paypal.com"),
            PhishingHeuristics.extractLinks("подтвердите: paypal(.)com")
        )
        assertEquals(listOf("bank.de"), PhishingHeuristics.extractLinks("bank{dot}de"))
    }

    @Test
    fun `obfuscated scheme is unmasked`() {
        // `hxxp://` — «безопасное» написание ссылки в спаме и чатах.
        assertTrue(PhishingHeuristics.isSuspicious("Срочно: hxxp://sberbank-online.ru/login"))
        assertEquals(
            listOf("http://sberbank-online.ru/login"),
            PhishingHeuristics.extractLinks("hxxp://sberbank-online.ru/login")
        )
        assertEquals(listOf("https://a.example"), PhishingHeuristics.extractLinks("hxxps://a.example"))
    }

    @Test
    fun `dot surrounded by spaces still forms a host`() {
        assertTrue(PhishingHeuristics.isSuspicious("Ваш счёт заблокирован: sberbank . ru"))
        assertEquals(listOf("sberbank.ru"), PhishingHeuristics.extractLinks("зайдите на sberbank . ru"))
    }

    @Test
    fun `new top level domains are links`() {
        // Раньше зоны были перечислены руками: `.icu`, `.app`, `.link`, `.рф` не считались ссылкой,
        // поэтому голый домен в свежей зоне проходил как «сообщение без ссылки».
        assertEquals(listOf("sberbank-online.icu"), PhishingHeuristics.extractLinks("зайдите на sberbank-online.icu"))
        assertEquals(listOf("evil.app"), PhishingHeuristics.extractLinks("ссылка: evil.app"))
        assertEquals(listOf("яндекс.рф"), PhishingHeuristics.extractLinks("зайдите на яндекс.рф"))
    }

    @Test
    fun `file names are not links`() {
        // Имя файла похоже на домен, но ссылкой не является.
        assertTrue(PhishingHeuristics.extractLinks("отчёт.pdf и photo.jpg").isEmpty())
        assertTrue(PhishingHeuristics.extractLinks("index.html, script.js, data.json").isEmpty())
    }

    @Test
    fun `homoglyph host is suspicious without keywords`() {
        // Кириллическая «а» в «аpple.com» — сама по себе атака, «давящее» слово не нужно.
        assertTrue(PhishingHeuristics.isSuspicious("Заходите на аpple.com"))
        assertTrue(PhishingHeuristics.hasMixedAlphabetHost("https://аpple.com/path"))
        assertFalse(PhishingHeuristics.hasMixedAlphabetHost("https://apple.com/path"))
        // Легитимный кириллический домен целиком кириллический — не находка.
        assertFalse(PhishingHeuristics.hasMixedAlphabetHost("https://яндекс.рф"))
        assertFalse(PhishingHeuristics.isSuspicious("Заходите на яндекс.рф"))
    }

    @Test
    fun `keywords are matched as words not substrings`() {
        // Бывшие ложные срабатывания: `код` в «кодексе», `карта` в «картоне».
        assertFalse(PhishingHeuristics.hasPhishingKeyword("Пришлите кодекс и картон"))
        assertFalse(PhishingHeuristics.isSuspicious("Пришлите кодекс и картон: https://example.com"))
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Пришлите код подтверждения"))
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Ваша карта заблокирована"))
    }

    @Test
    fun `keyword stems cover word forms`() {
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Подтверждено: перевод"))
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Аккаунт заблокирован"))
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Перейдите по ссылке"))
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Bitte bestätigen Sie jetzt"))
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Ihr Konto wurde gesperrt"))
    }

    @Test
    fun `keyword phrases are matched across separators`() {
        assertTrue(PhishingHeuristics.hasPhishingKeyword("Please Click-Here to win"))
        assertTrue(PhishingHeuristics.hasPhishingKeyword("click here"))
        assertFalse(PhishingHeuristics.hasPhishingKeyword("click the button here"))
    }

    @Test
    fun `text without keywords and homoglyphs stays clean`() {
        assertFalse(PhishingHeuristics.hasPhishingKeyword("Привет, как дела?"))
        assertFalse(PhishingHeuristics.isSuspicious("Смотрите https://example.com/news и всё"))
    }

    @Test
    fun `empty message has nothing to find`() {
        assertFalse(PhishingHeuristics.isSuspicious(""))
        assertFalse(PhishingHeuristics.hasPhishingKeyword(""))
        assertTrue(PhishingHeuristics.extractLinks("").isEmpty())
    }

    @Test
    fun `ordinary sentences do not become links`() {
        // Аббревиатуры и «сокращения» без домена не должны превращаться в ссылки.
        assertTrue(PhishingHeuristics.extractLinks("т.е. это не ссылка").isEmpty())
        assertTrue(PhishingHeuristics.extractLinks("Цена 10.50 EUR за литр").isEmpty())
    }
}
