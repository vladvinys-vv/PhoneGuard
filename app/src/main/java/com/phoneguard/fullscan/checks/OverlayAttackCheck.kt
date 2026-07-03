package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Overlay Attack (Tapjacking) Check.
 *
 * Malware использует SYSTEM_ALERT_WINDOW для наложения невидимых окон
 * поверх легитимных приложений, перехватывая клики пользователя.
 */
@Singleton
class OverlayAttackCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class OverlayResult(
        val appsWithOverlay: List<SuspiciousOverlayApp>,
        val hasDangerousOverlays: Boolean
    )

    data class SuspiciousOverlayApp(
        val packageName: String,
        val appName: String,
        val riskReason: String
    )

    // Приложения, которые могут использовать оверлеи для атак
    private val suspiciousOverlayPatterns = listOf(
        "screen.filter", "bluelight", "dimmer", "night.filter",
        "screen.dim", "overlay", "floating", "bubble",
        "chat.head", "pop-up"
    )

    fun performCheck(): OverlayResult {
        val pm = context.packageManager
        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val suspicious = installedApps.filter { appInfo ->
            val pkg = appInfo.packageName
            val appName = pm.getApplicationLabel(appInfo).toString()

            // Проверка: системное ли приложение
            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                              (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            // Suspicious если: не системное + подозрительное имя
            !isSystemApp && suspiciousOverlayPatterns.any { pattern ->
                pkg.contains(pattern, ignoreCase = true) ||
                appName.contains(pattern, ignoreCase = true)
            }
        }.map { appInfo ->
            val pkg = appInfo.packageName
            val appName = pm.getApplicationLabel(appInfo).toString()
            SuspiciousOverlayApp(pkg, appName, "Возможное использование overlay для tapjacking")
        }

        return OverlayResult(
            suspicious,
            suspicious.isNotEmpty()
        )
    }
}
