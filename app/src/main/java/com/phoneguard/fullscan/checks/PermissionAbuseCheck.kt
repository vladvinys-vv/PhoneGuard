package com.phoneguard.fullscan.checks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import com.phoneguard.R
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
        val riskyPermissions: List<PermissionGroup>,
        val riskLevel: RiskLevel,
        val explanation: String
    )

    enum class RiskLevel { CRITICAL, HIGH, MEDIUM }

    /**
     * Группы разрешений.
     *
     * Раньше роль ключей играли русские строки («Камера», «СМС»): логика сравнения
     * зависела от текста, а подписи в отчёте нельзя было перевести. Теперь enum —
     * источник истины, а `labelRes` даёт локализованное название.
     */
    enum class PermissionGroup(@StringRes val labelRes: Int) {
        CAMERA(R.string.perm_group_camera),
        MICROPHONE(R.string.perm_group_microphone),
        LOCATION(R.string.perm_group_location),
        CONTACTS(R.string.perm_group_contacts),
        SMS(R.string.perm_group_sms),
        PHONE(R.string.perm_group_phone),
        STORAGE(R.string.perm_group_storage)
    }

    // Опасные комбинации разрешений
    private val dangerousPermissionGroups = mapOf(
        PermissionGroup.CAMERA to listOf(Manifest.permission.CAMERA),
        PermissionGroup.MICROPHONE to listOf(Manifest.permission.RECORD_AUDIO),
        PermissionGroup.LOCATION to listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ),
        PermissionGroup.CONTACTS to listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        ),
        PermissionGroup.SMS to listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        ),
        PermissionGroup.PHONE to listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE
        ),
        PermissionGroup.STORAGE to listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        // POST_NOTIFICATIONS намеренно не входит в «опасные»: это самая безобидная
        // из runtime-разрешений, и раньше она давала ложные срабатывания.
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

            // Важно проверять именно выданные разрешения: приложение может
            // запрашивать камеру и микрофон и не получить их, но раньше оно всё
            // равно попадало в отчёт как «шпионское».
            val grantedPermissions = getGrantedPermissions(pkg)
            val grantedGroups = dangerousPermissionGroups.filterValues { perms ->
                perms.any { it in grantedPermissions }
            }

            if (grantedGroups.isEmpty()) return@mapNotNull null

            val (riskLevel, explanation) = assessRisk(grantedGroups.keys.toSet()) { groups ->
                localizedLabels(groups)
            }
            if (riskLevel == null || explanation == null) return@mapNotNull null

            RiskyApp(
                packageName = pkg,
                appName = appName,
                riskyPermissions = grantedGroups.keys.toList(),
                riskLevel = riskLevel,
                explanation = explanation.resolve(context)
            )
        }

        val criticalCount = riskyApps.count { it.riskLevel == RiskLevel.CRITICAL }
        return PermissionAbuseResult(riskyApps, criticalCount)
    }

    /** Разрешения из манифеста, реально выданные приложению. */
    private fun getGrantedPermissions(packageName: String): Set<String> {
        return getRequestedPermissions(packageName).filterTo(mutableSetOf()) { permission ->
            context.packageManager.checkPermission(permission, packageName) ==
                PackageManager.PERMISSION_GRANTED
        }
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

    /**
     * Оценка риска по набору выданных групп разрешений.
     *
     * `internal` и с параметром `labels` — чтобы тесты проверяли логику без
     * `Context`/`Resources` (иначе mockk пришлось бы учить всем `getString`
     * с vararg, а тест проверял бы перевод, а не риск).
     */
    internal fun assessRisk(
        grantedGroups: Set<PermissionGroup>,
        labels: (Collection<PermissionGroup>) -> String
    ): Pair<RiskLevel?, CheckDetail?> {
        val verdict = PermissionRiskPolicy.assess(grantedGroups.map { it.toPolicyGroup() }.toSet())
            ?: return null to null

        val level = when (verdict.level) {
            PermissionRiskPolicy.Level.CRITICAL -> RiskLevel.CRITICAL
            PermissionRiskPolicy.Level.HIGH -> RiskLevel.HIGH
            PermissionRiskPolicy.Level.MEDIUM -> RiskLevel.MEDIUM
        }
        val matched = grantedGroups.filter { it.toPolicyGroup() in verdict.matched }
        val detail = when (verdict.reason) {
            PermissionRiskPolicy.Reason.CRITICAL_COMBO ->
                CheckDetail(R.string.perm_risk_critical_combo, listOf(labels(matched)))
            PermissionRiskPolicy.Reason.SMS_PLUS_OTHER ->
                CheckDetail(R.string.perm_risk_sms_extra, listOf(labels(grantedGroups)))
            PermissionRiskPolicy.Reason.BACKGROUND_LOCATION ->
                CheckDetail(R.string.perm_risk_background_location)
            PermissionRiskPolicy.Reason.SENSITIVE_PAIR ->
                CheckDetail(R.string.perm_risk_many_sensitive, listOf(labels(matched)))
            PermissionRiskPolicy.Reason.MANY_SENSITIVE ->
                CheckDetail(R.string.perm_risk_many_sensitive, listOf(labels(grantedGroups)))
        }
        return level to detail
    }

    /** Соответствие групп разрешений чистым правилам риска (см. [PermissionRiskPolicy]). */
    private fun PermissionGroup.toPolicyGroup(): PermissionRiskPolicy.Group = when (this) {
        PermissionGroup.CAMERA -> PermissionRiskPolicy.Group.CAMERA
        PermissionGroup.MICROPHONE -> PermissionRiskPolicy.Group.MICROPHONE
        PermissionGroup.LOCATION -> PermissionRiskPolicy.Group.LOCATION
        PermissionGroup.CONTACTS -> PermissionRiskPolicy.Group.CONTACTS
        PermissionGroup.SMS -> PermissionRiskPolicy.Group.SMS
        PermissionGroup.PHONE -> PermissionRiskPolicy.Group.PHONE
        PermissionGroup.STORAGE -> PermissionRiskPolicy.Group.STORAGE
    }

    /** Локализованные названия групп через запятую. */
    private fun localizedLabels(groups: Collection<PermissionGroup>): String =
        groups.joinToString(", ") { context.getString(it.labelRes) }
}
