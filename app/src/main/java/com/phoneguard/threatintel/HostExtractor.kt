package com.phoneguard.threatintel

import java.util.Locale

/**
 * Выделение хоста из строки, которую прислали в SMS, из ленты OpenPhish или из лога.
 *
 * Раньше это делал `ThreatIntelClient` через `okhttp3.HttpUrl`, поэтому проверка не исполнялась
 * ни одним юнит-тестом — а от результата зависит, **уйдёт ли домен на проверку репутации вообще**.
 * Ошибка здесь тихая: функция вернула `null`, хост не попал в `hostsToCheck`, находки нет.
 *
 * Поведение:
 *  - схема (`http://`, `https://`, `//`) отбрасывается **без учёта регистра** — раньше
 *    `HTTPS://EVIL.COM` склеивался с префиксом и не разбирался вовсе;
 *  - отбрасываются `user:pass@`, порт, путь, query и fragment;
 *  - хост приводится к нижнему регистру, снимается завершающая точка (`evil.com.`);
 *  - IPv6 (`[::1]`), односоставные имена (`localhost`, `intranet`) и тестовые домены
 *    (`example.com`) не считаются целью репутационной проверки — возвращается `null`.
 */
internal object HostExtractor {

    /** Домены, на которых проверка бессмысленна: локальные и зарезервированные под примеры. */
    private val IGNORED_HOSTS = setOf("localhost", "example.com", "example.org", "example.net")

    /** Символы, которыми «обвешивают» ссылки в тексте сообщений: `(http://x.com),` и т.п. */
    private const val TRIM_CHARS = "\"'`,;)]><([."

    private const val SEPARATORS = "/?#\\"

    fun extractHost(rawUrl: String): String? {
        var value = rawUrl.trim().trim(*TRIM_CHARS.toCharArray())
        if (value.isEmpty()) return null

        // Схема: `http://`, `https://` или протокол-независимое `//host`.
        val schemeIndex = value.indexOf("://")
        if (schemeIndex >= 0) value = value.substring(schemeIndex + 3)
        while (value.startsWith("//")) value = value.substring(2)

        // Authority заканчивается на первом разделителе пути.
        val authority = value.takeWhile { it !in SEPARATORS }
        if (authority.isEmpty()) return null

        // `user:password@host` — хост после последней собаки.
        val withoutUserInfo = authority.substringAfterLast('@')
        if (withoutUserInfo.isEmpty()) return null

        // IPv6 в скобках — не домен: ленты URLhaus/OpenPhish оперируют именами.
        if (withoutUserInfo.startsWith("[")) return null

        val withoutPort = withoutUserInfo.substringBefore(':')
        var host = withoutPort.lowercase(Locale.ROOT).trim('.')
        // `www.` не меняет владельца домена, а в фидах его обычно нет: без нормализации
        // ссылка «www.evil.com» из SMS не совпала бы с записью «evil.com» и ушла без проверки.
        if (host.startsWith("www.") && '.' in host.removePrefix("www.")) {
            host = host.removePrefix("www.")
        }
        if (host.isEmpty() || '.' !in host) return null
        if (host in IGNORED_HOSTS) return null
        if (!host.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }) return null
        return host
    }
}
