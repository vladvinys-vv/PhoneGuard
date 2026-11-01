package com.phoneguard.fullscan.checks

import java.util.Locale

/**
 * Чистая эвристика фишинга в SMS — без Android, корутин и сети.
 *
 * Вынесена из [PhishingUrlChecker] отдельно, чтобы её можно было прогонять юнит-тестами без
 * `Context` и `ThreatIntelRepository`: раньше логика жила в классе с `contentResolver` и
 * OkHttp-репозиторием, поэтому ни один тест её фактически не исполнял.
 *
 * Правило: сообщение подозрительно, если в нём есть **и** ссылка, **и** «давящее» слово
 * («срочно», «заблокирован», «подтвердите», …). Ни одного из условий недостаточно: обычные
 * сообщения со ссылкой не должны попадать в находки, иначе проверка бесполезна.
 */
internal object PhishingHeuristics {

    private val LINK_TOKENS = listOf(
        "http://", "https://", "www.", ".ru/", ".com/", ".xyz", ".top", ".click", "bit.ly", "t.me/"
    )

    private val PHISHING_KEYWORDS = listOf(
        "перейдите", "получите", "выигрыш", "банк", "карта", "пароль", "код",
        "подтвердите", "срочно", "заблокирован", "spende", "gewinn", "konto",
        "sperre", "bestätigen", "click here", "verify"
    )

    /** Схемы, `www.`, сокращатели и голые домены в частых «фишинговых» зонах. */
    private val LINK_REGEX = Regex(
        """(?:https?://|www\.)[^\s<>"']+""" +
            """|(?:bit\.ly|t\.me|tinyurl\.com|goo\.gl)/[^\s<>"']+""" +
            """|\b[\w-]+(?:\.[\w-]+)*\.(?:ru|com|net|org|info|xyz|top|click|site|online|shop|de|su|io)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Локальная эвристика: ссылка + «давящие» ключевые слова. */
    fun isSuspicious(body: String): Boolean {
        val lower = body.lowercase(Locale.ROOT)
        val hasLink = LINK_TOKENS.any { lower.contains(it) } || extractLinks(body).isNotEmpty()
        val hasPhishingKeyword = PHISHING_KEYWORDS.any { lower.contains(it) }
        return hasLink && hasPhishingKeyword
    }

    /** Достаёт ссылки из текста сообщения (http/https, `www.`, сокращатели, t.me, голые домены). */
    fun extractLinks(body: String): List<String> =
        LINK_REGEX.findAll(body).map { it.value.trimEnd('.', ',', ')', ';', '!', '?', ':') }.toList()
}
