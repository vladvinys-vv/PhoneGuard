package com.phoneguard.fullscan.checks

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device Admin Abuse Check.
 *
 * Malware может получить права Device Admin для защиты от удаления.
 * Проверяет все активные device admin компоненты.
 */
@Singleton
class DeviceAdminAbuseCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DeviceAdminResult(
        val activeAdmins: List<SuspiciousAdmin>,
        val hasSuspiciousAdmins: Boolean
    )

    data class SuspiciousAdmin(
        val packageName: String,
        val appName: String,
        val componentName: String,
        val riskReason: String
    )

    // Известные легитимные device admin пакеты
    private val knownLegitAdmins = setOf(
        "com.google.android.gms",           // Google Play Services
        "com.android.settings",             // Settings
        "com.google.android.work",          // Android Enterprise
        "com.microsoft.intune",             // Intune MDM
        "com.vmware.workspace",             // VMware Workspace ONE
        "com.samsung.android.knox",         // Samsung Knox
        "com.android.enterprise"            // Android Enterprise
    )

    // Подозрительные паттерны
    private val suspiciousAdminPatterns = listOf(
        "admin", "device.admin", "policy",
        "security.manager", "lock", "wipe",
        "anti.theft", "spy"
    )

    fun performCheck(): DeviceAdminResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val pm = context.packageManager

        val activeAdmins = dpm.activeAdmins ?: emptyList<ComponentName>()

        val suspicious = activeAdmins.mapNotNull { component ->
            val pkg = component.packageName

            // Легитимные admin пропускаем
            if (knownLegitAdmins.contains(pkg)) return@mapNotNull null

            val appName = try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                "Неизвестное приложение ($pkg)"
            }

            val riskReason = if (suspiciousAdminPatterns.any { pattern ->
                pkg.contains(pattern, ignoreCase = true) ||
                appName.contains(pattern, ignoreCase = true)
            }) {
                "Подозрительное имя + права Device Admin"
            } else {
                "Неизвестный Device Admin (не из списка легитимных)"
            }

            SuspiciousAdmin(
                packageName = pkg,
                appName = appName,
                componentName = component.flattenToShortString(),
                riskReason = riskReason
            )
        }

        return DeviceAdminResult(
            suspicious,
            suspicious.isNotEmpty()
        )
    }
}
