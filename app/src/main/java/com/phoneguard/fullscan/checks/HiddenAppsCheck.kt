package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hidden Apps Check.
 *
 * Malware часто скрывает иконки из лаунчера (android.intent.category.LAUNCHER отсутствует),
 * но при этом остаётся установленным и может работать в фоне.
 */
@Singleton
class HiddenAppsCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class HiddenAppsResult(
        val hiddenApps: List<HiddenApp>,
        val totalCount: Int
    )

    data class HiddenApp(
        val packageName: String,
        val appName: String,
        val installTime: Long,
        val targetSdk: Int,
        val riskReason: String
    )

    // Подозрительные паттерны имён пакетов для скрытых приложений
    private val suspiciousHiddenPatterns = listOf(
        "com.android.system", "com.android.service",
        "com.google.service", "com.system.update",
        "update.service", "android.system"
    )

    fun performCheck(): HiddenAppsResult {
        val pm = context.packageManager
        val allInstalledApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val hiddenApps = allInstalledApps.filter { appInfo ->
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                              (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            if (isSystemApp) return@filter false

            // Проверка: есть ли launcher intent
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            launchIntent == null
        }.mapNotNull { appInfo ->
            val pkg = appInfo.packageName
            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) { pkg }

            val packageInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            } catch (_: Exception) { return@mapNotNull null }

            val riskReason = if (suspiciousHiddenPatterns.any { pattern ->
                pkg.contains(pattern, ignoreCase = true)
            }) {
                "Подозрительное имя пакета + скрыт из лаунчера"
            } else {
                "Скрыт из лаунчера (нет activity с LAUNCHER intent)"
            }

            HiddenAppsCheck.HiddenApp(
                packageName = pkg,
                appName = appName,
                installTime = packageInfo.firstInstallTime,
                targetSdk = packageInfo.applicationInfo.targetSdkVersion,
                riskReason = riskReason
            )
        }

        return HiddenAppsResult(hiddenApps, hiddenApps.size)
    }
}
