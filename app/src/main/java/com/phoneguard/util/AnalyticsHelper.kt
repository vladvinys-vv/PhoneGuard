package com.phoneguard.util

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val firebaseAnalytics: FirebaseAnalytics by lazy {
        FirebaseAnalytics.getInstance(context)
    }

    fun logScreenView(screenName: String) {
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
            param(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
        }
    }

    fun logEvent(eventName: String, params: Map<String, Any> = emptyMap()) {
        firebaseAnalytics.logEvent(eventName) {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> param(key, value)
                    is Int -> param(key, value.toLong())
                    is Long -> param(key, value)
                    is Boolean -> param(key, value)
                }
            }
        }
    }

    fun logCallBlocked(phoneNumber: String, isSms: Boolean) {
        logEvent("call_blocked", mapOf(
            "phone_number" to phoneNumber,
            "is_sms" to isSms
        ))
    }

    fun logFirewallBlock(packageName: String, ipAddress: String?) {
        logEvent("firewall_block", mapOf(
            "package_name" to packageName,
            "ip_address" to (ipAddress ?: "")
        ))
    }

    fun logScanCompleted(riskScore: Int, issuesFound: Int) {
        logEvent("scan_completed", mapOf(
            "risk_score" to riskScore,
            "issues_found" to issuesFound
        ))
    }

    fun logVaultImport(fileSize: Long, mimeType: String) {
        logEvent("vault_import", mapOf(
            "file_size" to fileSize,
            "mime_type" to mimeType
        ))
    }

    fun logConsentResult(granted: Boolean, permission: String) {
        logEvent("consent_result", mapOf(
            "granted" to granted,
            "permission" to permission
        ))
    }
}