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

    /** Явные ссылки: схема, `www.`, сокращатели. Квантификаторы простые — без вложенности. */
    private val EXPLICIT_LINK = Regex(
        """(?:https?://|www\.)[^\s<>"']+|(?:bit\.ly|t\.me|tinyurl\.com|goo\.gl)/[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )

    /** Зоны, в которых голый домен из SMS считается ссылкой (`.ru` без `/` раньше не ловился). */
    private val BARE_DOMAIN_ZONES = setOf(
        "ru", "com", "net", "org", "info", "xyz", "top", "click", "site", "online", "shop", "de", "su", "io"
    )

    /** Символы, которыми «обвешивают» ссылки в тексте: `(http://x.com),` и т.п. */
    private const val TRIM_CHARS = "\"'`,;)]}><[{([.!?:"

    /** Локальная эвристика: ссылка + «давящие» ключевые слова. */
    fun isSuspicious(body: String): Boolean {
        val lower = body.lowercase(Locale.ROOT)
        val hasLink = LINK_TOKENS.any { lower.contains(it) } || extractLinks(body).isNotEmpty()
        val hasPhishingKeyword = PHISHING_KEYWORDS.any { lower.contains(it) }
        return hasLink && hasPhishingKeyword
    }

    /**
     * Достаёт ссылки из текста сообщения: явные (схема, `www.`, сокращатели) и голые домены
     * в частых зонах.
     *
     * Разбор идёт по словам и простыми регулярками: прежняя регулярка с вложенным
     * квантификатором (`(?:\.[\w-]+)*`) на тексте вида «a.a.a.a…» роняла проверку
     * `StackOverflowError` — то есть одно входящее SMS могло уронить полный скан.
     */
    fun extractLinks(body: String): List<String> {
        val links = mutableListOf<String>()
        for (rawToken in body.split(' ', '\t', '\n', '\r')) {
            val token = rawToken.trim(*TRIM_CHARS.toCharArray())
            if (token.isEmpty()) continue
            val explicit = EXPLICIT_LINK.findAll(token).map { it.value.trim(*TRIM_CHARS.toCharArray()) }
            val found = explicit.filter { it.isNotEmpty() }.toList()
            if (found.isNotEmpty()) {
                links += found
            } else if (isBareDomain(token)) {
                links += token
            }
        }
        return links
    }

    /** `sberbank-online.ru` — да; `10.50`, `т.е`, `v1.2` — нет. */
    private fun isBareDomain(token: String): Boolean {
        if ('.' !in token) return false
        if (token.any { it == '/' || it == '\\' || it == '?' || it == '#' || it == '@' || it == ':' }) return false
        val labels = token.lowercase(Locale.ROOT).split('.')
        if (labels.size < 2) return false
        if (!BARE_DOMAIN_ZONES.contains(labels.last())) return false
        return labels.all { label ->
            label.isNotEmpty() &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}
