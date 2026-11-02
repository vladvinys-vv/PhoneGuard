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

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun performCheck(): DeviceAdminResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val pm = context.packageManager

        val activeAdmins = dpm.activeAdmins ?: emptyList<ComponentName>()

        val suspicious = activeAdmins.mapNotNull { component ->
            val pkg = component.packageName
            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val label = appInfo?.let { pm.getApplicationLabel(it).toString() }

            // Решение — в чистом DeviceAdminRiskPolicy (порядок проверок зафиксирован
            // JVM-тестами): свои права, известный легитимный admin, системный admin,
            // удалённое приложение, подозрительное имя, сторонний администратор.
            val verdict = DeviceAdminRiskPolicy.assess(
                packageName = pkg,
                appName = label,
                isSystemApp = appInfo != null && isSystemApp(appInfo),
                ownPackage = context.packageName
            )
            if (!DeviceAdminRiskPolicy.isReportable(verdict)) return@mapNotNull null

            val riskReason = when (verdict) {
                DeviceAdminRiskPolicy.Verdict.APP_REMOVED ->
                    context.getString(R.string.device_admin_app_removed)
                DeviceAdminRiskPolicy.Verdict.SUSPICIOUS_NAME ->
                    context.getString(R.string.device_admin_suspicious_name)
                else -> context.getString(R.string.device_admin_third_party)
            }

            SuspiciousAdmin(
                packageName = pkg,
                appName = label ?: context.getString(R.string.device_admin_unknown_app, pkg),
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
