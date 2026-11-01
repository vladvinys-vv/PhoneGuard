package com.phoneguard.fullscan.checks

import android.content.Context
import android.provider.Telephony
import com.phoneguard.R
import com.phoneguard.threatintel.ThreatIntelRepository
import com.phoneguard.threatintel.ThreatVerdict
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Эвристический анализ SMS на фишинговые ссылки.
 *
 * Два уровня:
 *  1. **Локальные правила** (всегда): ссылка + «социальные» ключевые слова.
 *  2. **Reputation-проверка доменов** (только при включённой настройке):
 *     домены из сообщений проверяются через [ThreatIntelRepository]
 *     (URLhaus / OpenPhish). Наружу уходит только имя хоста.
 *
 * Проверка не блокирует SMS — она лишь формирует находки для полного скана.
 */
@Singleton
class PhishingUrlChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val threatIntelRepository: ThreatIntelRepository
) {
    data class PhishingResult(
        val suspiciousMessages: List<String>,
        val checkedCount: Int,
        val details: List<String>,
        val maliciousHosts: List<String> = emptyList(),
        val intelCheckedHosts: Int = 0
    )

    suspend fun checkRecentMessages(maxMessages: Int = 200): PhishingResult = withContext(Dispatchers.IO) {
        val suspicious = mutableListOf<String>()
        val details = mutableListOf<String>()
        val hostsToCheck = linkedSetOf<String>()
        var checked = 0

        try {
            val cr = context.contentResolver
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE)
            val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $maxMessages"
            val cursor = cr.query(uri, projection, null, null, sortOrder)
            cursor?.use { c ->
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (c.moveToNext()) {
                    val body = c.getString(bodyIdx) ?: continue
                    checked++
                    if (isSuspicious(body)) {
                        suspicious.add(body.takeLast(120))
                        extractLinks(body).forEach { link ->
                            threatIntelRepository.extractHostOrNull(link)?.let { hostsToCheck.add(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            details.add(context.getString(R.string.phishing_no_sms_access))
        }

        val maliciousHosts = mutableListOf<String>()
        var intelChecked = 0
        if (hostsToCheck.isNotEmpty() && threatIntelRepository.isEnabled()) {
            hostsToCheck.take(MAX_INTEL_LOOKUPS).forEach { host ->
                intelChecked++
                val verdict = runCatching { threatIntelRepository.checkHost(host) }
                    .getOrNull()?.verdict ?: ThreatVerdict.UNKNOWN
                if (verdict.isBlocking) maliciousHosts.add(host)
            }
            if (maliciousHosts.isNotEmpty()) {
                details.add(context.getString(R.string.phishing_malicious_hosts, maliciousHosts.joinToString(", ")))
            }
        } else if (hostsToCheck.isNotEmpty()) {
            details.add(context.getString(R.string.phishing_intel_disabled))
        }

        details.add(context.getString(R.string.phishing_checked_count, checked))
        details.add(context.getString(R.string.phishing_limit_sms_permission))
        details.add(context.getString(R.string.phishing_limit_heuristic))
        return@withContext PhishingResult(
            suspiciousMessages = suspicious,
            checkedCount = checked,
            details = details,
            maliciousHosts = maliciousHosts,
            intelCheckedHosts = intelChecked
        )
    }

    /**
     * Локальная эвристика: ссылка + «давящие» ключевые слова.
     * Видимость `internal` нужна юнит-тестам.
     */
    internal fun isSuspicious(body: String): Boolean = PhishingHeuristics.isSuspicious(body)

    /** Достаёт ссылки из текста сообщения (http/https, `www.`, сокращатели, t.me). */
    internal fun extractLinks(body: String): List<String> = PhishingHeuristics.extractLinks(body)

    companion object {
        private const val TAG = "PhishingUrlChecker"

        /** Максимум сетевых проверок за один скан — чтобы не «залипать» на фиде. */
        private const val MAX_INTEL_LOOKUPS = 10


    }
}
