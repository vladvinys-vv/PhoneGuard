package com.phoneguard.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.phoneguard.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivacyScannerRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val packageManager = context.packageManager
    private val dangerousPermissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    @SuppressLint("NewApi")
    fun getInstalledApps(): Flow<List<PrivacyApp>> = flow {
        val apps = withContext(Dispatchers.IO) {
            val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            installedPackages.mapNotNull { pkg ->
                try {
                    // PackageInfo.applicationInfo помечен в SDK как nullable: без проверки
                    // первое же обращение (`appInfo.uid`) — потенциальный NPE на пакетах
                    // без application-блока (часть системных и «полупустых» приложений).
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    val appIcon = packageManager.getApplicationIcon(appInfo)
                    // Одним системным вызовом на приложение, а не на каждое разрешение:
                    // раньше для 200 приложений × 15 разрешений выполнялось ~3000
                    // IPC-запросов к AppOps, и экран открывался секундами.
                    val requested = pkg.requestedPermissions.orEmpty()
                    val lastUsedByPermission = getLastUsedTimes(
                        packageName = pkg.packageName,
                        uid = appInfo.uid,
                        permissions = requested.filter { dangerousPermissions.contains(it) }
                    )
                    val appPermissions = requested.map { perm ->
                        val isDangerous = dangerousPermissions.contains(perm)
                        AppPermission(perm, isDangerous, lastUsedByPermission[perm])
                    }
                    val category = getAppCategory(appInfo)
                    val app = PrivacyApp(
                        packageName = pkg.packageName,
                        appName = appName,
                        icon = appIcon,
                        permissions = appPermissions,
                        hasExcessivePermissions = false,
                        installTime = pkg.firstInstallTime,
                        category = category
                    ).copy(hasExcessivePermissions = PermissionHeuristics.hasExcessivePermissions(app))
                    app
                } catch (e: Exception) {
                    null
                }
            }
        }
        emit(apps)
    }

    /**
     * Время последнего использования разрешения (по AppOps).
     *
     * Используется только публичный API: `getOpsForPackage(uid, pkg, ops)`.
     * Исторические API (`getHistoricalPackageOps`) помечены `@SystemApi` и
     * недоступны обычным приложениям — раньше здесь был именно такой вызов,
     * который не компилировался против публичного SDK.
     */
    /**
     * Время последнего использования опасных разрешений приложения (по AppOps).
     *
     * Все запрошенные операции читаются одним вызовом `getOpsForPackage`.
     * Используется только публичный API: исторические `getHistoricalPackageOps`
     * помечены `@SystemApi` и обычным приложениям недоступны.
     */
    @SuppressLint("ServiceCast")
    private fun getLastUsedTimes(
        packageName: String,
        uid: Int,
        permissions: List<String>
    ): Map<String, Long> {
        if (permissions.isEmpty()) return emptyMap()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return emptyMap()
        val opByPermission = permissions.mapNotNull { permission ->
            getOpStrForPermission(permission)?.let { permission to it }
        }.toMap()
        if (opByPermission.isEmpty()) return emptyMap()

        return try {
            @Suppress("DEPRECATION")
            val ops = appOps.getOpsForPackage(uid, packageName, opByPermission.values.toTypedArray())
            val timeByOp = ops?.firstOrNull()?.ops.orEmpty()
                .associate { it.opStr to it.time }
            opByPermission.mapNotNull { (permission, opStr) ->
                timeByOp[opStr]?.takeIf { it > 0 }?.let { permission to it }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** AppOps-строка для разрешения (единственный корректный источник — `OPSTR_*`). */
    private fun getOpStrForPermission(permission: String): String? = when (permission) {
        Manifest.permission.CAMERA -> AppOpsManager.OPSTR_CAMERA
        Manifest.permission.RECORD_AUDIO -> AppOpsManager.OPSTR_RECORD_AUDIO
        Manifest.permission.ACCESS_FINE_LOCATION -> AppOpsManager.OPSTR_FINE_LOCATION
        Manifest.permission.ACCESS_COARSE_LOCATION -> AppOpsManager.OPSTR_COARSE_LOCATION
        Manifest.permission.READ_CONTACTS -> AppOpsManager.OPSTR_READ_CONTACTS
        Manifest.permission.READ_CALL_LOG -> AppOpsManager.OPSTR_READ_CALL_LOG
        Manifest.permission.READ_SMS -> AppOpsManager.OPSTR_READ_SMS
        else -> null
    }

    private fun getAppCategory(appInfo: ApplicationInfo): AppCategory {
        return when {
            appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> AppCategory.UTILITIES
            else -> {
                val appName = appInfo.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
                when {
                    appName.contains("social") || appName.contains("chat") || appName.contains("messenger") -> AppCategory.SOCIAL
                    appName.contains("game") || appName.contains("entertainment") || appName.contains("video") -> AppCategory.ENTERTAINMENT
                    appName.contains("productivity") || appName.contains("office") || appName.contains("calendar") -> AppCategory.PRODUCTIVITY
                    appName.contains("tool") || appName.contains("flashlight") || appName.contains("calculator") -> AppCategory.TOOLS
                    else -> AppCategory.UNKNOWN
                }
            }
        }
    }
}
