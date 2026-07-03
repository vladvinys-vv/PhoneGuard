package com.phoneguard.fullscan.checks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permission Abuse Check.
 *
 * Обнаруживает приложения с опасными комбинациями разрешений,
 * которые могут указывать на шпионское ПО или malware.
 */
@Singleton
class PermissionAbuseCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class PermissionAbuseResult(
        val riskyApps: List<RiskyApp>,
        val criticalCount: Int
    )

    data class RiskyApp(
        val packageName: String,
        val appName: String,
        val riskyPermissions: List<String>,
        val riskLevel: RiskLevel,
        val explanation: String
    )

    enum class RiskLevel { CRITICAL, HIGH, MEDIUM }

    // Опасные комбинации разрешений
    private val dangerousPermissionGroups = mapOf(
        "Камера" to listOf(Manifest.permission.CAMERA),
        "Микрофон" to listOf(Manifest.permission.RECORD_AUDIO),
        "Местоположение" to listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ),
        "Контакты" to listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        "СМС" to listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS),
        "Телефон" to listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE),
        "Хранилище" to listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
        "Уведомления" to listOf(Manifest.permission.POST_NOTIFICATIONS)
    )

    // Критические комбинации (набор групп = шпионское поведение)
    private val criticalCombinations = listOf(
        setOf("Камера", "Микрофон", "Местоположение"),
        setOf("СМС", "Контакты", "Местоположение"),
        setOf("Камера", "Микрофон"),
        setOf("СМС", "Телефон"),
        setOf("Микрофон", "Местоположение")
    )

    fun performCheck(): PermissionAbuseResult {
        val pm = context.packageManager
        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val riskyApps = installedApps.mapNotNull { appInfo ->
            val pkg = appInfo.packageName
            val appName = pm.getApplicationLabel(appInfo).toString()

            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                              (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            if (isSystemApp) return@mapNotNull null

            val permissions = getRequestedPermissions(pkg)
            val grantedGroups = dangerousPermissionGroups.filterKeys { group ->
                dangerousPermissionGroups[group]!!.any { perm ->
                    permissions.contains(perm)
                }
            }

            if (grantedGroups.isEmpty()) return@mapNotNull null

            val (riskLevel, explanation) = assessRisk(grantedGroups.keys)
            if (riskLevel == null) return@mapNotNull null

            RiskyApp(
                packageName = pkg,
                appName = appName,
                riskyPermissions = grantedGroups.keys.toList(),
                riskLevel = riskLevel,
                explanation = explanation
            )
        }

        val criticalCount = riskyApps.count { it.riskLevel == RiskLevel.CRITICAL }
        return PermissionAbuseResult(riskyApps, criticalCount)
    }

    private fun getRequestedPermissions(packageName: String): Set<String> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            packageInfo.requestedPermissions?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun assessRisk(grantedGroups: Set<String>): Pair<RiskLevel?, String?> {
        // Critical: шпионские комбинации
        for (combo in criticalCombinations) {
            if (grantedGroups.containsAll(combo)) {
                return RiskLevel.CRITICAL to "Опасная комбинация разрешений: ${combo.joinToString(", ")}"
            }
        }

        // High: доступ к СМС + что-то ещё
        if (grantedGroups.contains("СМС") && grantedGroups.size >= 2) {
            return RiskLevel.HIGH to "СМС + дополнительные разрешения: ${grantedGroups.joinToString(", ")}"
        }

        // Medium: одиночные чувствительные разрешения
        if (grantedGroups.contains("Местоположение") && grantedGroups.size == 1) {
            return RiskLevel.MEDIUM to "Фоновое местоположение"
        }

        if (grantedGroups.size >= 3) {
            return RiskLevel.HIGH to "Множество чувствительных разрешений: ${grantedGroups.joinToString(", ")}"
        }

        return null to null
    }
}
