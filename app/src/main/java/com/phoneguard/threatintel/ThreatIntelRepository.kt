package com.phoneguard.threatintel

import android.util.Log
import com.phoneguard.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Точка входа для reputation-проверок доменов.
 *
 * Правила:
 *  1. **Opt-in.** Любой сетевой запрос возможен только если пользователь явно
 *     включил «Проверка доменов в threat-intelligence базах» в настройках
 *     ([PreferencesManager.isThreatIntelEnabled], по умолчанию `false`).
 *     Это сохраняет обещание оффлайн-работы приложения из README.
 *  2. **Fail-open.** Ошибка сети → [ThreatVerdict.UNKNOWN]; пользователь никогда
 *     не остаётся без функциональности из-за недоступности источника.
 *  3. **Минимизация данных.** Наружу уходит только имя хоста.
 *  4. **Кэш.** Ответы кэшируются в памяти (TTL), фид OpenPhish — отдельно,
 *     чтобы не скачивать его на каждую проверку.
 */
@Singleton
class ThreatIntelRepository @Inject constructor(
    private val client: ThreatIntelClient,
    private val preferencesManager: PreferencesManager
) {

    private data class CacheEntry(val result: ThreatIntelResult, val expiresAt: Long)

    /** Access-ordered LRU-кэш: самые старые записи вытесняются вручную в [store]. */
    private val hostCache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    @Volatile
    private var openPhishHosts: Set<String> = emptySet()

    @Volatile
    private var openPhishFetchedAt: Long = 0L

    /** Включена ли сетевая проверка пользователем. */
    suspend fun isEnabled(): Boolean = preferencesManager.isThreatIntelEnabled.first()

    /**
     * Проверяет одну ссылку/хост.
     *
     * @param url полный URL либо голое имя домена.
     * @return результат проверки; [ThreatVerdict.UNKNOWN], если проверка выключена
     *         или источники недоступны.
     */
    suspend fun checkUrl(url: String): ThreatIntelResult {
        val host = client.extractHost(url) ?: return ThreatIntelResult.unknown(url)
        return checkHost(host)
    }

    /** Проверяет хост (с кэшем). */
    suspend fun checkHost(host: String): ThreatIntelResult {
        val normalizedHost = host.trim().lowercase(Locale.ROOT)
        if (normalizedHost.isEmpty()) return ThreatIntelResult.unknown(host)

        cached(normalizedHost)?.let { return it.copy(source = "${it.source}+${ThreatIntelSources.CACHE}") }

        if (!isEnabled()) {
            return ThreatIntelResult.unknown(normalizedHost, ThreatIntelSources.LOCAL, details = "disabled")
        }

        // 1. Открытый фид фишинговых URL (кэшируется целиком).
        val feed = openPhishHosts()
        if (feed.contains(normalizedHost) || feedContainsSubdomain(feed, normalizedHost)) {
            return store(
                ThreatIntelResult(
                    host = normalizedHost,
                    verdict = ThreatVerdict.SUSPICIOUS,
                    source = ThreatIntelSources.OPENPHISH,
                    details = "listed_in_feed"
                )
            )
        }

        // 2. Точечная проверка в URLhaus.
        val urlhaus = client.checkHostUrlhaus(normalizedHost)
        return store(urlhaus)
    }

    /** Извлекает нормализованный хост из URL (для локальных проверок без сети). */
    fun extractHostOrNull(url: String): String? = client.extractHost(url)

    /** Есть ли уже закэшированный результат (без обращения к сети). */
    fun peekCached(host: String): ThreatIntelResult? = cached(host.trim().lowercase(Locale.ROOT))

    fun clearCache() {
        synchronized(hostCache) { hostCache.clear() }
        openPhishHosts = emptySet()
        openPhishFetchedAt = 0L
    }

    private suspend fun openPhishHosts(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - openPhishFetchedAt < OPENPHISH_TTL_MS) return openPhishHosts

        val hosts = client.fetchOpenPhishHosts()
        if (hosts.isNotEmpty()) {
            openPhishHosts = hosts
            openPhishFetchedAt = now
            Log.d(TAG, "OpenPhish feed loaded: ${hosts.size} hosts")
        } else {
            // Не кэшируем пустой ответ как «успех», чтобы не залипнуть на 6 часов без данных.
            openPhishFetchedAt = now - OPENPHISH_TTL_MS + EMPTY_FEED_RETRY_MS
        }
        return openPhishHosts
    }

    private fun feedContainsSubdomain(feed: Set<String>, host: String): Boolean {
        var candidate = host
        while (true) {
            val dotIndex = candidate.indexOf('.')
            if (dotIndex < 0 || dotIndex == candidate.lastIndex) return false
            candidate = candidate.substring(dotIndex + 1)
            if (feed.contains(candidate)) return true
        }
    }

    private fun cached(host: String): ThreatIntelResult? {
        synchronized(hostCache) {
            val entry = hostCache[host] ?: return null
            if (entry.expiresAt < System.currentTimeMillis()) {
                hostCache.remove(host)
                return null
            }
            return entry.result
        }
    }

    private fun store(result: ThreatIntelResult): ThreatIntelResult {
        // «Неизвестно» кэшируем короче, чтобы не залипнуть на недоступном источнике.
        val ttl = if (result.verdict == ThreatVerdict.UNKNOWN) UNKNOWN_TTL_MS else TTL_MS
        synchronized(hostCache) {
            if (hostCache.size >= MAX_CACHE_ENTRIES && !hostCache.containsKey(result.host)) {
                hostCache.entries.iterator().let { iterator -> if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
            }
            hostCache[result.host] = CacheEntry(result, System.currentTimeMillis() + ttl)
        }
        return result
    }

    companion object {
        private const val TAG = "ThreatIntelRepo"
        private const val MAX_CACHE_ENTRIES = 256
        private const val TTL_MS = 6 * 60 * 60 * 1000L
        private const val UNKNOWN_TTL_MS = 10 * 60 * 1000L
        private const val OPENPHISH_TTL_MS = 6 * 60 * 60 * 1000L
        private const val EMPTY_FEED_RETRY_MS = 60 * 60 * 1000L
    }
}
