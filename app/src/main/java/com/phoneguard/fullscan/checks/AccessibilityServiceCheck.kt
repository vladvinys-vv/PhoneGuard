package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accessibility Service Abuse Check.
 *
 * Malware часто запрашивает Accessibility Service для чтения экрана,
 * перехвата ввода, обхода защит банковских приложений.
 */
@Singleton
class AccessibilityServiceCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class AccessibilityResult(
        val suspiciousServices: List<SuspiciousAccessibilityService>,
        val totalCount: Int
    )

    data class SuspiciousAccessibilityService(
        val packageName: String,
        val appName: String,
        val riskReason: String
    )

    // Пакеты, которые часто эксплуатируют accessibility
    private val suspiciousPackagePatterns = listOf(
        "autoclick", "auto.click", "accessibility.click",
        "spy", "monitor", "keylog", "screenshot",
        "recorder", "tracker", "watcher", "grabber"
    )

    fun performCheck(): AccessibilityResult {
        val enabledServices = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
        } catch (_: Exception) { "" }

        if (enabledServices.isBlank()) {
            return AccessibilityResult(emptyList(), 0)
        }

        val pm = context.packageManager
        val serviceComponents = enabledServices.split("/")
        val packageNames = serviceComponents
            .mapNotNull { it.substringBeforeOrNull("/") }
            .distinct()

        val suspicious = packageNames.mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()

                val riskReason = suspiciousPackagePatterns.firstOrNull { pattern ->
                    pkg.contains(pattern, ignoreCase = true) ||
                    appName.contains(pattern, ignoreCase = true)
                }?.let { "Подозрительное имя пакета/приложения: $it" }
                    ?: "Accessibility service без явной необходимости"

                SuspiciousAccessibilityService(pkg, appName, riskReason)
            } catch (_: PackageManager.NameNotFoundException) {
                // Пакет удалён, но сервис остался в настройках
                SuspiciousAccessibilityService(
                    pkg, "Удалённое приложение",
                    "Сервис удалённого приложения всё ещё активен"
                )
            }
        }

        return AccessibilityResult(suspicious, packageNames.size)
    }

    private fun String.substringBeforeOrNull(delimiter: String): String? {
        val index = indexOf(delimiter)
        return if (index >= 0) substring(0, index) else this
    }
}
