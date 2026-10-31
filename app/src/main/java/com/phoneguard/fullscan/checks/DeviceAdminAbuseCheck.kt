package com.phoneguard.fullscan.checks

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.phoneguard.R
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

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun performCheck(): DeviceAdminResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val pm = context.packageManager

        val activeAdmins = dpm.activeAdmins ?: emptyList<ComponentName>()

        val suspicious = activeAdmins.mapNotNull { component ->
            val pkg = component.packageName

            // Собственные права Device Admin (анти-кража самого PhoneGuard) — не находка.
            if (pkg == context.packageName) return@mapNotNull null
            // Легитимные admin из списка пропускаем.
            if (knownLegitAdmins.contains(pkg)) return@mapNotNull null

            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

            // Предустановленные администраторы (MDM от производителя, корпоративные образы)
            // — норма; раньше любой из них попадал в отчёт как «неизвестный Device Admin».
            if (appInfo != null && isSystemApp(appInfo)) return@mapNotNull null

            val appName = appInfo?.let { pm.getApplicationLabel(it).toString() }
                ?: context.getString(R.string.device_admin_unknown_app, pkg)

            val riskReason = when {
                appInfo == null -> context.getString(R.string.device_admin_app_removed)
                suspiciousAdminPatterns.any { pattern ->
                    pkg.contains(pattern, ignoreCase = true) ||
                        appName.contains(pattern, ignoreCase = true)
                } -> context.getString(R.string.device_admin_suspicious_name)
                else -> context.getString(R.string.device_admin_third_party)
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
