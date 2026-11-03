package com.phoneguard.util

import android.content.Context
import android.util.Log
import com.phoneguard.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальный «аналитический» слой.
 *
 * Раньше здесь был Firebase Analytics, который отправлял на серверы Google в том
 * числе заблокированные номера телефонов (`logCallBlocked`) — это прямо
 * противоречило обещанию «Zero telemetry» из README и требованиям GDPR/DSGVO.
 *
 * Сейчас события обрабатываются только на устройстве: в debug-сборке они пишутся
 * в logcat, в release — отбрасываются. Никаких сетевых вызовов и никаких
 * идентификаторов пользователя.
 */
@Singleton
class AnalyticsHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Счётчики текущей сессии — полезны для отладки, никуда не передаются (см. [SessionCounters]). */
    private val counters = SessionCounters()

    fun logEvent(eventName: String, params: Map<String, Any> = emptyMap()) {
        record(eventName, params)
    }

    /**
     * Блокировка звонка/SMS.
     *
     * У метода намеренно **нет** параметра с номером: раньше подпись требовала его,
     * и вызывающий код передавал номер в «аналитику» (до появления этого класса —
     * прямиком в Firebase). Теперь передать персональные данные сюда невозможно.
     */
    fun logCallBlocked(isSms: Boolean) {
        record("call_blocked", mapOf("is_sms" to isSms))
    }

    fun logScanCompleted(riskScore: Int, issuesFound: Int) {
        record("scan_completed", mapOf("risk_score" to riskScore, "issues_found" to issuesFound))
    }

    fun logVaultImport(fileSize: Long, mimeType: String) {
        record("vault_import", mapOf("file_size" to fileSize, "mime_type" to mimeType))
    }

    /** Количество событий с таким именем за текущую сессию. */
    fun sessionCount(eventName: String): Int = counters.count(eventName)

    /** Снимок всех счётчиков сессии (копия) — для отладочных экранов и тестов. */
    fun sessionSnapshot(): Map<String, Int> = counters.snapshot()

    private fun record(eventName: String, params: Map<String, Any>) {
        counters.record(eventName)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "event=$eventName params=$params (local only)")
        }
    }

    companion object {
        private const val TAG = "PhoneGuardEvents"
    }
}
