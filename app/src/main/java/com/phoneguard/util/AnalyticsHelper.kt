package com.phoneguard.util

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val firebaseAnalytics: FirebaseAnalytics? by lazy {
        runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull()
    }

    fun logScreenView(screenName: String) {
        val params = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
        }
        firebaseAnalytics?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, params)
    }

    fun logEvent(eventName: String, params: Map<String, Any> = emptyMap()) {
        val bundle = Bundle()
        params.forEach { (key, value) ->
            when (value) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putLong(key, value.toLong())
                is Long -> bundle.putLong(key, value)
                is Boolean -> bundle.putBoolean(key, value)
            }
        }
        firebaseAnalytics?.logEvent(eventName, bundle)
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