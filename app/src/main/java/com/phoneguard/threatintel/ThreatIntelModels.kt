package com.phoneguard.threatintel

/**
 * Вердикт по домену/URL, полученный из локальных эвристик или из внешней
 * threat-intelligence базы.
 */
enum class ThreatVerdict {
    /** Проверка не выполнялась либо источник недоступен — трактуем как «неизвестно», не блокируем. */
    UNKNOWN,

    /** Источник ответил, что домен/URL не известен как вредоносный. */
    CLEAN,

    /** Домен в фидовых списках (например, OpenPhish) — фишинг. */
    SUSPICIOUS,

    /** Домен/URL подтверждён как вредоносный (например, URLhaus). */
    MALICIOUS;

    val isBlocking: Boolean get() = this == MALICIOUS || this == SUSPICIOUS
}

/** Результат проверки одного хоста. */
data class ThreatIntelResult(
    val host: String,
    val verdict: ThreatVerdict,
    val source: String,
    val details: String? = null,
    val checkedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun unknown(host: String, source: String = "none", details: String? = null) =
            ThreatIntelResult(host = host, verdict = ThreatVerdict.UNKNOWN, source = source, details = details)
    }
}

/** Источники данных, которые поддерживает [ThreatIntelClient]. */
object ThreatIntelSources {
    const val URLHAUS = "urlhaus.abuse.ch"
    const val OPENPHISH = "openphish.com"
    const val LOCAL = "local"
    const val CACHE = "cache"
}
