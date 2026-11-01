package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.phoneguard.util.DeviceHeuristics
import com.phoneguard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accessibility Service Abuse Check.
 *
 * Malware часто запрашивает Accessibility Service для чтения экрана,
 * перехвата ввода, обхода защит банковских приложений.
 *
 * Исправления:
 *  - список включённых служб разбирался через `split("/")`. Настройка имеет вид
 *    `пакет/класс:пакет/класс`, поэтому «пакетами» оказывались строки вида
 *    `com.pkg.Service:com.evil.pkg`, `getApplicationInfo` для них падал, и отчёт
 *    заполнялся фальшивыми «удалёнными приложениями»;
 *  - TalkBack, Switch Access и прочие системные службы больше не считаются
 *    подозрительными — они отфильтрованы [DeviceHeuristics].
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

    fun performCheck(): AccessibilityResult {
        val enabledServices = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        }

        val packageNames = DeviceHeuristics.accessibilityPackages(
            enabledServices,
            context.packageName
        )
        if (packageNames.isEmpty()) {
            return AccessibilityResult(emptyList(), 0)
        }

        val pm = context.packageManager
        val suspicious = packageNames
            .filterNot(DeviceHeuristics::isTrustedAccessibilityPackage)
            .map { pkg ->
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }

                // Признаки живут в AccessibilityHeuristics — там они и проверяются тестами.
                val matchedPattern = AccessibilityHeuristics.matchingPattern(pkg, appName)

                SuspiciousAccessibilityService(
                    packageName = pkg,
                    appName = appName ?: context.getString(R.string.accessibility_unknown_app_name),
                    riskReason = when {
                        matchedPattern != null -> context.getString(
                            R.string.accessibility_reason_suspicious_name,
                            matchedPattern
                        )
                        appName == null -> context.getString(R.string.accessibility_reason_removed_app)
                        else -> context.getString(R.string.accessibility_reason_third_party)
                    }
                )
            }

        return AccessibilityResult(suspicious, packageNames.size)
    }
}
