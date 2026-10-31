package com.phoneguard.data.repository

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import com.phoneguard.malware.ApkSignatureScanner
import com.phoneguard.util.DeviceHeuristics
import com.phoneguard.util.HiddenAppHeuristics
import com.phoneguard.model.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpywareCheckRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apkSignatureScanner: ApkSignatureScanner
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

            val deduplicated = (deviceAdminApps + accessibilityServices + sideloadedApps + hiddenApps)
                .distinctBy { it.packageName }

            val combinedApps = deduplicated.map { app ->
                val indicators = mutableListOf<SpywareIndicator>()
                if (deviceAdminApps.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.DEVICE_ADMIN)
                if (accessibilityServices.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.ACCESSIBILITY_SERVICE)
                if (sideloadedApps.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.SIDELOADED)
                if (hiddenApps.any { it.packageName == app.packageName }) indicators.add(SpywareIndicator.HIDDEN_LAUNCHER)
                indicators.addAll(inspectSignature(app.packageName))

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
                // Сумма по приложениям может превысить 100 (10 sideloaded приложений = 250),
                // а на экране это шкала 0..100.
                totalRiskScore = totalScore.coerceIn(0, 100),
                deviceAdminApps = deviceAdminApps,
                accessibilityServices = accessibilityServices,
                sideloadedApps = sideloadedApps,
                hiddenApps = hiddenApps
            )
        }
        emit(scanResult)
    }

    /**
     * Активные Device Admin.
     *
     * Собственный администратор PhoneGuard (штатная функция анти-кражи) и
     * предустановленные администраторы не являются находками — раньше они попадали в
     * список «шпионского ПО» как `HIGH`.
     */
    private fun getDeviceAdminApps(): List<SpywareApp> {
        return try {
            val activeAdmins = devicePolicyManager?.activeAdmins ?: emptyList()
            activeAdmins
                .map { it.packageName }
                .distinct()
                .filterNot { it == context.packageName }
                .filterNot { pkg -> isSystemPackage(pkg) }
                .mapNotNull { pkg ->
                    createSpywareAppFromPackage(pkg, listOf(SpywareIndicator.DEVICE_ADMIN))
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Включённые службы доступности.
     *
     * Считаются только сторонние службы: TalkBack, Switch Access и прочие системные
     * включены на многих устройствах и раньше попадали в отчёт как «HIGH риск».
     */
    @SuppressLint("WrongConstant")
    private fun getAccessibilityServices(): List<SpywareApp> {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            DeviceHeuristics.untrustedAccessibilityPackages(enabledServices, context.packageName)
                .filterNot { pkg -> isSystemPackage(pkg) }
                .mapNotNull { pkg ->
                    createSpywareAppFromPackage(pkg, listOf(SpywareIndicator.ACCESSIBILITY_SERVICE))
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getSideloadedApps(apps: List<ApplicationInfo>): List<SpywareApp> {
        val legitimateInstallers = setOf(
            "com.android.vending",       // Google Play
            "com.samsung.android.galaxyapps", // Samsung Galaxy Store
            "com.huawei.appmarket",      // Huawei AppGallery
            "com.amazon.venezia",         // Amazon Appstore
            "com.xiaomi.market",         // Xiaomi GetApps
            "com.opera.appstore",        // Opera Mobile Store
            "com.aapte.android.market",  // Aptoide
            null                          // ADB install / unknown
        )
        return apps.mapNotNull { appInfo ->
            val installer = packageManager.getInstallerPackageName(appInfo.packageName)
            if (installer !in legitimateInstallers && appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                createSpywareAppFromPackage(appInfo.packageName, listOf(SpywareIndicator.SIDELOADED))
            } else {
                null
            }
        }
    }

    /**
     * Приложения без иконки в лаунчере.
     *
     * Раньше в список попадало любое несистемное приложение без launcher-интента —
     * наборы иконок, виджеты, плагины камеры. Теперь используется та же эвристика,
     * что и в `HiddenAppsCheck` (activity + опасное разрешение или подозрительное имя).
     */
    private fun getHiddenLauncherApps(apps: List<ApplicationInfo>): List<SpywareApp> {
        return apps.mapNotNull { appInfo ->
            if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            if (packageManager.getLaunchIntentForPackage(appInfo.packageName) != null) {
                return@mapNotNull null
            }

            val packageInfo = queryPackageInfo(appInfo.packageName) ?: return@mapNotNull null
            val requested = packageInfo.requestedPermissions?.toSet().orEmpty()
            val isCandidate = HiddenAppHeuristics.isHiddenAppCandidate(
                packageInfo.activities?.size ?: 0,
                requested,
                appInfo.packageName
            )
            if (!isCandidate) return@mapNotNull null

            createSpywareAppFromPackage(
                appInfo.packageName,
                listOf(SpywareIndicator.HIDDEN_LAUNCHER)
            )
        }
    }

    private fun queryPackageInfo(packageName: String): android.content.pm.PackageInfo? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS).toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Предустановленное приложение (в т.ч. обновлённое системное). */
    private fun isSystemPackage(packageName: String): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (_: Exception) {
        false
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

    /**
     * Подпись APK разбирается локально через [ApkSignatureScanner] (apk-parser).
     *
     * `PackageManager` сообщает только текущую подпись и не даёт алгоритм/срок
     * действия сертификата, поэтому именно здесь ловятся пересобранные APK:
     * debug-ключ у приложения не из IDE, слабый алгоритм (MD5/SHA-1), истёкший
     * сертификат и подменённый после установки файл.
     */
    private suspend fun inspectSignature(packageName: String): List<SpywareIndicator> {
        val info = try {
            apkSignatureScanner.inspectInstalledPackage(packageName)
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        return buildList {
            if (info.isInstalledFromDebugKey) add(SpywareIndicator.DEBUG_SIGNATURE)
            if (info.hasWeakSignature) add(SpywareIndicator.WEAK_SIGNATURE)
            if (info.isCertificateExpired) add(SpywareIndicator.EXPIRED_CERTIFICATE)
            if (info.apkModifiedAfterInstall) add(SpywareIndicator.MODIFIED_AFTER_INSTALL)
        }
    }

    private fun calculateRiskLevel(indicators: List<SpywareIndicator>): RiskLevel {
        return when {
            indicators.contains(SpywareIndicator.DEVICE_ADMIN) && indicators.contains(SpywareIndicator.ACCESSIBILITY_SERVICE) -> RiskLevel.CRITICAL
            indicators.contains(SpywareIndicator.DEVICE_ADMIN) || indicators.contains(SpywareIndicator.ACCESSIBILITY_SERVICE) -> RiskLevel.HIGH
            indicators.contains(SpywareIndicator.MODIFIED_AFTER_INSTALL) -> RiskLevel.HIGH
            indicators.containsAll(listOf(SpywareIndicator.DEBUG_SIGNATURE, SpywareIndicator.SIDELOADED)) -> RiskLevel.HIGH
            indicators.size >= 2 -> RiskLevel.HIGH
            indicators.isNotEmpty() -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
}
