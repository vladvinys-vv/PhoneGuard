package com.phoneguard.threatintel

import android.os.SystemClock
import android.util.Log
import com.phoneguard.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Момент, раньше которого фид заново не запрашивается (монотонные часы).
     *
     * Раньше здесь было «время последней загрузки», а решение принималось сравнением с TTL
     * **вне блокировки**: несколько параллельных проверок видели устаревший фид и каждая
     * тянула его заново (до 8 МБ трафика). Теперь есть и явный «когда можно», и блокировка.
     */
    @Volatile
    private var nextFeedAttemptElapsed: Long = 0L

    /** Single-flight: пока один поток грузит фид, остальные ждут его результат. */
    private val feedMutex = Mutex()

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
        nextFeedAttemptElapsed = 0L
    }

    /**
     * Фид OpenPhish с single-flight-загрузкой.
     *
     * Быстрый путь (фид свежий) не берёт блокировку; медленный — берёт и **перепроверяет**
     * условие: пока поток ждал, фид мог загрузить кто-то другой, и второй запрос на 8 МБ
     * никому не нужен.
     */
    private suspend fun openPhishHosts(): Set<String> {
        if (ThreatFeedRefreshPolicy.decide(SystemClock.elapsedRealtime(), nextFeedAttemptElapsed) ==
            ThreatFeedRefreshPolicy.Decision.WAIT
        ) {
            return openPhishHosts
        }

        return feedMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (ThreatFeedRefreshPolicy.decide(now, nextFeedAttemptElapsed) ==
                ThreatFeedRefreshPolicy.Decision.WAIT
            ) {
                return@withLock openPhishHosts
            }

            val hosts = runCatching { client.fetchOpenPhishHosts() }.getOrNull()
            when {
                hosts == null -> {
                    // Ошибка сети: короткая пауза, старый фид остаётся в работе.
                    nextFeedAttemptElapsed =
                        ThreatFeedRefreshPolicy.nextAttemptAfterFailure(SystemClock.elapsedRealtime())
                    Log.w(TAG, "OpenPhish feed request failed")
                }
                hosts.isNotEmpty() -> {
                    openPhishHosts = hosts
                    nextFeedAttemptElapsed =
                        ThreatFeedRefreshPolicy.nextAttemptAfterSuccess(SystemClock.elapsedRealtime())
                    Log.d(TAG, "OpenPhish feed loaded: ${hosts.size} hosts")
                }
                else -> {
                    // Не кэшируем пустой ответ как «успех», чтобы не залипнуть на 6 часов без данных.
                    nextFeedAttemptElapsed =
                        ThreatFeedRefreshPolicy.nextAttemptAfterEmpty(SystemClock.elapsedRealtime())
                    Log.w(TAG, "OpenPhish feed returned no hosts")
                }
            }
            openPhishHosts
        }
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
            // Монотонные часы: перевод системного времени не должен ни «состарить» кэш,
            // ни держать запись с вердиктом бесконечно.
            if (entry.expiresAt < SystemClock.elapsedRealtime()) {
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
            hostCache[result.host] = CacheEntry(result, SystemClock.elapsedRealtime() + ttl)
        }
        return result
    }

    companion object {
        private const val TAG = "ThreatIntelRepo"
        private const val MAX_CACHE_ENTRIES = 256
        private const val TTL_MS = 6 * 60 * 60 * 1000L
        private const val UNKNOWN_TTL_MS = 10 * 60 * 1000L
    }
}
