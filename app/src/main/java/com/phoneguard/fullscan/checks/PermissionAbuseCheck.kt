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

    // Критические комбинации (набор групп = шпионское поведение)
    private val criticalCombinations = listOf(
        setOf(PermissionGroup.CAMERA, PermissionGroup.MICROPHONE, PermissionGroup.LOCATION),
        setOf(PermissionGroup.SMS, PermissionGroup.CONTACTS, PermissionGroup.LOCATION),
        setOf(PermissionGroup.CAMERA, PermissionGroup.MICROPHONE),
        setOf(PermissionGroup.SMS, PermissionGroup.PHONE),
        setOf(PermissionGroup.MICROPHONE, PermissionGroup.LOCATION)
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

            val (riskLevel, explanation) = assessRisk(grantedGroups.keys) { groups ->
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
        // Critical: шпионские комбинации
        for (combo in criticalCombinations) {
            if (grantedGroups.containsAll(combo)) {
                return RiskLevel.CRITICAL to CheckDetail(
                    R.string.perm_risk_critical_combo,
                    listOf(labels(combo))
                )
            }
        }

        // High: доступ к СМС + что-то ещё
        if (PermissionGroup.SMS in grantedGroups && grantedGroups.size >= 2) {
            return RiskLevel.HIGH to CheckDetail(
                R.string.perm_risk_sms_extra,
                listOf(labels(grantedGroups))
            )
        }

        // Medium: одиночные чувствительные разрешения
        if (PermissionGroup.LOCATION in grantedGroups && grantedGroups.size == 1) {
            return RiskLevel.MEDIUM to CheckDetail(R.string.perm_risk_background_location)
        }

        if (grantedGroups.size >= 3) {
            return RiskLevel.HIGH to CheckDetail(
                R.string.perm_risk_many_sensitive,
                listOf(labels(grantedGroups))
            )
        }

        return null to null
    }

    /** Локализованные названия групп через запятую. */
    private fun localizedLabels(groups: Collection<PermissionGroup>): String =
        groups.joinToString(", ") { context.getString(it.labelRes) }
}
