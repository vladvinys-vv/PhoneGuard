package com.phoneguard.threatintel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP-клиент для публичных threat-intelligence фидов
 * ([urlhaus.abuse.ch](https://urlhaus.abuse.ch/api/) и
 * [openphish.com](https://openphish.com/)).
 *
 * Сетевой слой построен на [OkHttp](https://github.com/square/okhttp):
 *  - короткие таймауты (проверка не должна задерживать сканирование);
 *  - никаких редиректов «на всякий случай» и авто-повторов;
 *  - любая ошибка сети/парсинга превращается в [ThreatVerdict.UNKNOWN]
 *    (fail-open: мы не блокируем пользователя из-за недоступности сервиса);
 *  - наружу уходит только имя хоста, без полного URL, query-параметров и тела SMS;
 *  - ответ фида читается построчно с ограничением размера: подменённый или
 *    «бесконечный» ответ не должен съесть память телефона.
 *
 * Клиент сам ничего не кэширует и не решает, можно ли вообще ходить в сеть —
 * это делает [ThreatIntelRepository] на основе пользовательской настройки.
 */
@Singleton
class ThreatIntelClient @Inject constructor(
    private val httpClient: OkHttpClient
) {

    /** Переопределяется в тестах (MockWebServer). */
    internal var urlhausHostEndpoint: String = URLHAUS_HOST_ENDPOINT
    /** Переопределяется в тестах (MockWebServer). */
    internal var openPhishFeedUrl: String = OPENPHISH_FEED_URL
    internal var userAgent: String = USER_AGENT

    /**
     * Проверяет хост в URLhaus (база вредоносных URL/ботнет-инфраструктуры).
     *
     * Ответ API: `{"query_status":"is_host", "urls":[...]}` — хост известен;
     * `{"query_status":"no_results"}` — не найден.
     */
    suspend fun checkHostUrlhaus(host: String): ThreatIntelResult = withContext(Dispatchers.IO) {
        // Locale.ROOT: турецкая локаль превращает «I» в «ı», и хост перестаёт совпадать с фидом.
        val normalizedHost = host.trim().lowercase(Locale.ROOT)
        if (normalizedHost.isEmpty()) return@withContext ThreatIntelResult.unknown(host)

        val request = Request.Builder()
            .url(urlhausHostEndpoint)
            .post(FormBody.Builder().add("host", normalizedHost).build())
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ThreatIntelResult.unknown(
                        normalizedHost,
                        ThreatIntelSources.URLHAUS,
                        details = statusDetail(response.code)
                    )
                }
                val body = response.body?.string().orEmpty()
                parseUrlhausResponse(normalizedHost, body)
            }
        } catch (e: Exception) {
            Log.d(TAG, "URLhaus lookup failed for $normalizedHost: ${e.message}")
            ThreatIntelResult.unknown(normalizedHost, ThreatIntelSources.URLHAUS, details = e.message)
        }
    }

    /**
     * Загружает открытый фид OpenPhish (построчный список URL) и выделяет из него
     * множество хостов. Фид большой, поэтому результат кэширует вызывающая сторона.
     */
    suspend fun fetchOpenPhishHosts(): Set<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(openPhishFeedUrl)
            .header("User-Agent", userAgent)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "OpenPhish feed HTTP ${response.code}")
                    return@withContext emptySet()
                }
                val body = response.body ?: return@withContext emptySet()
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_FEED_BYTES) {
                    Log.w(TAG, "OpenPhish feed слишком большой: $declaredLength байт")
                    return@withContext emptySet()
                }
                val hosts = LinkedHashSet<String>()
                var lineCount = 0
                // Построчное чтение вместо `body.string()`: фид целиком в памяти не нужен.
                body.source().use { source ->
                    while (lineCount < MAX_FEED_LINES && !source.exhausted()) {
                        extractHost(source.readUtf8Line()?.trim().orEmpty())?.let { hosts += it }
                        lineCount++
                    }
                }
                hosts
            }
        } catch (e: Exception) {
            Log.d(TAG, "OpenPhish feed download failed: ${e.message}")
            emptySet()
        }
    }

    /**
     * Выделяет хост из ссылки или строки ленты.
     *
     * Разбор живёт в чистом [HostExtractor] (без OkHttp), чтобы его поведение проверялось тестами:
     * ошибка здесь означает молча пропущенный домен, а не видимую поломку.
     */
    fun extractHost(rawUrl: String): String? = HostExtractor.extractHost(rawUrl)

    /** Понятная причина отказа вместо голого кода: API abuse.ch может требовать Auth-Key. */
    private fun statusDetail(code: Int): String = when (code) {
        401, 403 -> "auth_required"
        429 -> "rate_limited"
        else -> "HTTP $code"
    }

    private fun parseUrlhausResponse(host: String, body: String): ThreatIntelResult {
        return try {
            val json = JSONObject(body)
            val status = json.optString("query_status")
            when (status) {
                "is_host", "ok", "is_url" -> ThreatIntelResult(
                    host = host,
                    verdict = ThreatVerdict.MALICIOUS,
                    source = ThreatIntelSources.URLHAUS,
                    details = json.optString("host_status")
                        .ifBlank { json.optString("url_status") }
                        .ifBlank { "listed" }
                )
                "no_results" -> ThreatIntelResult(
                    host = host,
                    verdict = ThreatVerdict.CLEAN,
                    source = ThreatIntelSources.URLHAUS
                )
                else -> ThreatIntelResult.unknown(host, ThreatIntelSources.URLHAUS, details = status)
            }
        } catch (e: Exception) {
            ThreatIntelResult.unknown(host, ThreatIntelSources.URLHAUS, details = "unparseable response")
        }
    }

    companion object {
        private const val TAG = "ThreatIntel"
        private const val URLHAUS_HOST_ENDPOINT = "https://urlhaus-api.abuse.ch/v1/host/"
        private const val OPENPHISH_FEED_URL = "https://openphish.com/feed.txt"
        private const val USER_AGENT = "PhoneGuard/1.0 (Android security app; threat-intel lookup)"

        /** Хосты, которые бессмысленно проверять через фиды. */
        /** Предохранители для публичного фида (защита от гигантских/подменённых ответов). */
        private const val MAX_FEED_LINES = 50_000
        private const val MAX_FEED_BYTES = 8L * 1024 * 1024

        /** Таймауты для DI-провайдера [OkHttpClient]. */
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 5L
        const val CALL_TIMEOUT_SECONDS = 10L
    }
}
