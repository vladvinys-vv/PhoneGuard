package com.phoneguard.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.phoneguard.model.*

@Singleton
class SpywareCheckRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val packageManager = context.packageManager
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    fun scanForSpyware(): Flow<SpywareScanResult> = flow {
        emit(
            SpywareScanResult(
                apps = emptyList(),
                totalRiskScore = 0,
                deviceAdminApps = emptyList(),
                accessibilityServices = emptyList(),
                sideloadedApps = emptyList(),
                hiddenApps = emptyList()
            )
        )

        val scanResult = withContext(Dispatchers.IO) {
            val allApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val deviceAdminApps = getDeviceAdminApps()
            val accessibilityServices = getAccessibilityServices()
            val sideloadedApps = getSideloadedApps(allApps)
            val hiddenApps = getHiddenLauncherApps(allApps)

            val combinedApps = (deviceAdminApps + accessibilityServices + sideloadedApps + hiddenApps)
                .distinctBy { it.packageName }
                .map { app ->
                    val indicators = mutableListOf<SpywareIndicator>()
                    if (deviceAdminApps.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.DEVICE_ADMIN)
                    if (accessibilityServices.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.ACCESSIBILITY_SERVICE)
                    if (sideloadedApps.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.SIDELOADED)
                    if (hiddenApps.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.HIDDEN_LAUNCHER)

                    val riskLevel = calculateRiskLevel(indicators)
                    app.copy(indicators = indicators, riskLevel = riskLevel)
                }

            val totalScore = combinedApps.sumOf {
                when (it.riskLevel) {
                    RiskLevel.LOW -> 10
                    RiskLevel.MEDIUM -> 25
                    RiskLevel.HIGH -> 50
                    RiskLevel.CRITICAL -> 100
                }
            }

            SpywareScanResult(
                apps = combinedApps,
                totalRiskScore = totalScore,
                deviceAdminApps = deviceAdminApps,
                accessibilityServices = accessibilityServices,
                sideloadedApps = sideloadedApps,
                hiddenApps = hiddenApps
            )
        }
        emit(scanResult)
    }

    private fun getDeviceAdminApps(): List<SpywareApp> {
        return try {
            val activeAdmins = devicePolicyManager?.activeAdmins ?: emptyList()
            activeAdmins.mapNotNull { component ->
                createSpywareAppFromPackage(component.packageName, listOf(SpywareIndicator.DEVICE_ADMIN))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("WrongConstant")
    private fun getAccessibilityServices(): List<SpywareApp> {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            enabledServices.split(":").mapNotNull { serviceStr ->
                if (serviceStr.isNotBlank()) {
                    val component = ComponentName.unflattenFromString(serviceStr)
                    component?.packageName?.let { pkgName ->
                        createSpywareAppFromPackage(pkgName, listOf(SpywareIndicator.ACCESSIBILITY_SERVICE))
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getSideloadedApps(apps: List<ApplicationInfo>): List<SpywareApp> {
        return apps.mapNotNull { appInfo ->
            val installer = packageManager.getInstallerPackageName(appInfo.packageName)
            if (installer != "com.android.vending" && appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                createSpywareAppFromPackage(appInfo.packageName, listOf(SpywareIndicator.SIDELOADED))
            } else {
                null
            }
        }
    }

    private fun getHiddenLauncherApps(apps: List<ApplicationInfo>): List<SpywareApp> {
        return apps.mapNotNull { appInfo ->
            val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent == null && appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                createSpywareAppFromPackage(appInfo.packageName, listOf(SpywareIndicator.HIDDEN_LAUNCHER))
            } else {
                null
            }
        }
    }

    private fun createSpywareAppFromPackage(
        packageName: String,
        indicators: List<SpywareIndicator>
    ): SpywareApp? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = packageManager.getApplicationIcon(appInfo)
            SpywareApp(
                packageName = packageName,
                appName = appName,
                icon = appIcon,
                indicators = indicators,
                riskLevel = RiskLevel.MEDIUM
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateRiskLevel(indicators: List<SpywareIndicator>): RiskLevel {
        return when {
            indicators.contains(SpywareIndicator.DEVICE_ADMIN) && indicators.contains(SpywareIndicator.ACCESSIBILITY_SERVICE) -> RiskLevel.CRITICAL
            indicators.contains(SpywareIndicator.DEVICE_ADMIN) || indicators.contains(SpywareIndicator.ACCESSIBILITY_SERVICE) -> RiskLevel.HIGH
            indicators.size >= 2 -> RiskLevel.HIGH
            indicators.isNotEmpty() -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
}
