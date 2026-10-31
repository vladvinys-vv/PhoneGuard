package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.phoneguard.R
import com.phoneguard.util.HiddenAppHeuristics
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
        /** Человекочитаемая причина, уже локализованная. */
        val riskReason: String,
        /**
         * Имя пакета похоже на системное/маскирующееся.
         *
         * Флаг нужен вызывающему коду: раньше он искал подстроку «Подозрительное» в
         * [riskReason], то есть зависел от русского текста и переставал работать
         * при любой смене формулировки или языка.
         */
        val isSuspiciousName: Boolean = false,
        /** Опасные разрешения, которые запрашивает приложение. */
        val dangerousPermissions: List<String> = emptyList()
    )

    /** `PackageInfo` с activity и списком разрешений (флаги различаются до/после API 33). */
    private fun queryPackageInfo(packageName: String): android.content.pm.PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS).toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS
            )
        }
    } catch (_: Exception) {
        null
    }

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
            val packageInfo = queryPackageInfo(pkg) ?: return@mapNotNull null
            // PackageInfo.applicationInfo в SDK nullable — проверяем явно, иначе
            // `packageInfo.applicationInfo.targetSdkVersion` падал бы NPE.
            val packageAppInfo = packageInfo.applicationInfo ?: return@mapNotNull null

            val requested = packageInfo.requestedPermissions?.toSet().orEmpty()
            val dangerous = HiddenAppHeuristics.dangerousPermissions(requested)
            val hasSuspiciousName = HiddenAppHeuristics.hasSuspiciousName(pkg)

            // Приложение вообще без activity — это библиотека/плагин/аддон (иконки,
            // виджеты, камера-паки), а не «спрятанная» программа; эвристика это учитывает.
            if (!HiddenAppHeuristics.isHiddenAppCandidate(
                    packageInfo.activities?.size ?: 0,
                    requested,
                    pkg
                )
            ) {
                return@mapNotNull null
            }

            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) { pkg }

            val permissionNames = dangerous.joinToString(", ") { it.substringAfterLast('.') }
            val riskReason = when {
                hasSuspiciousName && dangerous.isNotEmpty() ->
                    context.getString(R.string.hidden_app_reason_suspicious_perms, permissionNames)
                hasSuspiciousName -> context.getString(R.string.hidden_app_reason_suspicious)
                else -> context.getString(R.string.hidden_app_reason_perms, permissionNames)
            }

            HiddenApp(
                packageName = pkg,
                appName = appName,
                installTime = packageInfo.firstInstallTime,
                targetSdk = packageAppInfo.targetSdkVersion,
                riskReason = riskReason,
                isSuspiciousName = hasSuspiciousName,
                dangerousPermissions = dangerous.toList()
            )
        }

        return HiddenAppsResult(hiddenApps, hiddenApps.size)
    }
}
