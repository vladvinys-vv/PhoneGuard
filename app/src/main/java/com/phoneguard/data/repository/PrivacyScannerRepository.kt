package com.phoneguard.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.phoneguard.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
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
        emit(emptyList())
        val apps = withContext(Dispatchers.IO) {
            val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            installedPackages.mapNotNull { pkg ->
                try {
                    val appInfo = pkg.applicationInfo
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    val appIcon = packageManager.getApplicationIcon(appInfo)
                    val appPermissions = pkg.requestedPermissions?.map { perm ->
                        val isDangerous = dangerousPermissions.contains(perm)
                        val lastUsed = getPermissionLastUsedTime(pkg.packageName, perm)
                        AppPermission(perm, isDangerous, lastUsed)
                    } ?: emptyList()
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

    @SuppressLint("ServiceCast", "NewApi")
    private fun getPermissionLastUsedTime(packageName: String, permission: String): Long? {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val opCode = getOpCodeForPermission(permission) ?: return null

        return try {
            val opStr = getOpStrForPermission(permission) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val startTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
                appOps?.getHistoricalPackageOps(packageName, startTime, System.currentTimeMillis())?.let {
                    for (pkgOp in it.packageOps) {
                        for (entry in pkgOp.ops) {
                            if (entry.opStr == opStr) {
                                return@let entry.lastAccessTime
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getOpCodeForPermission(permission: String): Int? {
        return when (permission) {
            Manifest.permission.CAMERA -> AppOpsManager.OPSTR_CAMERA
            Manifest.permission.RECORD_AUDIO -> AppOpsManager.OPSTR_RECORD_AUDIO
            Manifest.permission.ACCESS_FINE_LOCATION -> AppOpsManager.OPSTR_FINE_LOCATION
            Manifest.permission.ACCESS_COARSE_LOCATION -> AppOpsManager.OPSTR_COARSE_LOCATION
            else -> null
        }
    }

    private fun getOpStrForPermission(permission: String): String? {
        return when (permission) {
            Manifest.permission.CAMERA -> AppOpsManager.OPSTR_CAMERA
            Manifest.permission.RECORD_AUDIO -> AppOpsManager.OPSTR_RECORD_AUDIO
            Manifest.permission.ACCESS_FINE_LOCATION -> AppOpsManager.OPSTR_FINE_LOCATION
            Manifest.permission.ACCESS_COARSE_LOCATION -> AppOpsManager.OPSTR_COARSE_LOCATION
            else -> null
        }
    }

    private fun getAppCategory(appInfo: ApplicationInfo): AppCategory {
        return when {
            appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> AppCategory.UTILITIES
            else -> {
                val appName = appInfo.loadLabel(packageManager).toString().lowercase()
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
