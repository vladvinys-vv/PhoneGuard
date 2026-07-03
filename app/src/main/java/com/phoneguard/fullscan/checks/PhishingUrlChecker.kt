package com.phoneguard.fullscan.checks

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhishingUrlChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class PhishingResult(
        val suspiciousMessages: List<String>,
        val checkedCount: Int,
        val details: List<String>
    )

    fun checkRecentMessages(maxMessages: Int = 200): PhishingResult {
        val suspicious = mutableListOf<String>()
        val details = mutableListOf<String>()
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
                    }
                }
            }
        } catch (_: Exception) {
            details.add("Не удалось проверить SMS: нет доступа или нет данных.")
        }

        details.add("Проверено SMS: $checked")
        details.add("— Ограничение: без READ_SMS доступ к SMS может быть закрыт.")
        details.add("— Данная проверка — эвристика, не заменяет антифишинговые сервисы.")
        return PhishingResult(suspicious, checked, details)
    }

    private fun isSuspicious(body: String): Boolean {
        val lower = body.lowercase()
        val tokens = listOf("http://", "https://", "www.", ".ru/", ".com/", ".xyz", ".top", ".click", "bit.ly", "t.me/")
        val keywords = listOf("перейдите", "получите", "выигрыш", "банк", "карта", "пароль", "код", "подтвердите", "срочно")
        val hasLink = tokens.any { lower.contains(it) }
        val hasPhishingKeyword = keywords.any { lower.contains(it) }
        return hasLink && hasPhishingKeyword
    }
}
