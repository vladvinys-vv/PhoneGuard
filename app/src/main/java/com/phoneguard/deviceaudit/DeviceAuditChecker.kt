package com.phoneguard.deviceaudit

import android.Manifest
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phoneguard.R
import com.phoneguard.util.DeviceHeuristics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Чек всего телефона»: проверяет состояние самого устройства, а не только
 * приложения, — блокировку экрана, шифрование, отладку, обновления, свободное
 * место, установленные приложения, разрешения и т.п.
 *
 * Отличия от `FullScanOrchestrator`: полный скан ищет malware/угрозы, а этот
 * аудит отвечает на вопрос «в каком состоянии телефон прямо сейчас» и даёт
 * пользователю конкретный список действий. Он же используется как 16-й шаг
 * полного сканирования.
 *
 * Все проверки обёрнуты в try/catch и при отсутствии доступа возвращают
 * нейтральный результат: аудит не должен падать из-за особенностей прошивки.
 */
@Singleton
class DeviceAuditChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val packageManager: PackageManager get() = context.packageManager

    suspend fun performAudit(): DeviceAuditReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<AuditItem>()
        // Список пакетов нужен трём проверкам — перечисляем его один раз,
        // иначе `getInstalledApplications` вызывался трижды (это дорого).
        val apps = installedApps()
        items += checkScreenLock()
        items += checkEncryption()
        items += checkSelinux()
        items += checkUnknownSources(apps)
        items += checkAdb()
        items += checkDeveloperOptions()
        items += checkSecurityPatch()
        items += checkPackageVerifier()
        items += checkVpn()
        items += checkFreeSpace()
        items += checkInstalledApps(apps)
        items += checkOutdatedApps(apps)
        items += checkDebuggableApps(apps)
        items += checkAccessibilityServices()
        items += checkNotifications()
        items += checkBatteryOptimization()
        items += checkCorePermissions()
        items += checkDeviceAdmins()
        items += checkOsVersion()
        items += checkDeviceModel()
        DeviceAuditReport.from(items)
    }

    /**
     * Локализованный текст проверки — нужен отчётам полного сканирования,
     * которые хранят готовые строки, а не ресурсы.
     */
    fun getString(@StringRes resId: Int, vararg args: Any): String =
        context.getString(resId, *args)

    // ---------------------------------------------------------------- security

    private fun checkScreenLock(): AuditItem {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val secure = try {
            keyguard?.isDeviceSecure == true
        } catch (_: Exception) {
            false
        }
        return AuditItem(
            id = "screen_lock",
            category = AuditCategory.SECURITY,
            status = if (secure) AuditStatus.OK else AuditStatus.CRITICAL,
            titleRes = R.string.audit_screen_lock_title,
            detailRes = if (secure) R.string.audit_screen_lock_ok else R.string.audit_screen_lock_missing,
            recommendationRes = if (secure) null else R.string.audit_screen_lock_rec
        )
    }

    private fun checkEncryption(): AuditItem {
        val status = try {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
                ?.storageEncryptionStatus
        } catch (_: Exception) {
            null
        }
        val encrypted = status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
            status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY ||
            status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
        // «Не поддерживается»/нет ответа — это не «данные не зашифрованы»: на части
        // прошивок API не отдаёт статус, а пугать пользователя нечем помочь нельзя.
        val unverifiable = status == null ||
            status == DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED
        return AuditItem(
            id = "encryption",
            category = AuditCategory.SECURITY,
            status = when {
                encrypted -> AuditStatus.OK
                unverifiable -> AuditStatus.INFO
                else -> AuditStatus.CRITICAL
            },
            titleRes = R.string.audit_encryption_title,
            detailRes = when {
                encrypted -> R.string.audit_encryption_ok
                unverifiable -> R.string.audit_encryption_unknown
                else -> R.string.audit_encryption_missing
            },
            recommendationRes = if (!encrypted && !unverifiable) R.string.audit_encryption_rec else null
        )
    }

    private fun checkSelinux(): AuditItem {
        val enforcing = try {
            File("/sys/fs/selinux/enforce").readText().trim() == "1"
        } catch (_: Exception) {
            // Файла нет или нет доступа — не пугаем пользователя ложным выводом.
            true
        }
        return AuditItem(
            id = "selinux",
            category = AuditCategory.SECURITY,
            status = if (enforcing) AuditStatus.OK else AuditStatus.WARNING,
            titleRes = R.string.audit_selinux_title,
            detailRes = if (enforcing) R.string.audit_selinux_enforcing else R.string.audit_selinux_permissive,
            recommendationRes = if (enforcing) null else R.string.audit_selinux_rec
        )
    }

    /**
     * Установка из неизвестных источников.
     *
     * Глобальный `INSTALL_NON_MARKET_APPS` читается только на старых Android; начиная с
     * Android 8 право выдаётся **по приложению** (`REQUEST_INSTALL_PACKAGES` + AppOps).
     * Раньше проверялся только устаревший флаг, поэтому на современных версиях пункт
     * всегда был зелёным, даже если десяток приложений может ставить APK.
     */
    private fun checkUnknownSources(apps: List<ApplicationInfo>): AuditItem {
        val legacyAllowed = try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                0
            ) == 1
        } catch (_: Exception) {
            false
        }
        val modernAllowed = appsAllowedToInstallApks(apps)

        return when {
            legacyAllowed -> AuditItem(
                id = "unknown_sources",
                category = AuditCategory.APPS,
                status = AuditStatus.WARNING,
                titleRes = R.string.audit_unknown_sources_title,
                detailRes = R.string.audit_unknown_sources_on,
                recommendationRes = R.string.audit_unknown_sources_rec
            )
            modernAllowed.isNotEmpty() -> AuditItem(
                id = "unknown_sources",
                category = AuditCategory.APPS,
                status = AuditStatus.INFO,
                titleRes = R.string.audit_unknown_sources_title,
                detailRes = R.string.audit_unknown_sources_apps,
                detailArgs = listOf(modernAllowed.size.toString())
            )
            else -> AuditItem(
                id = "unknown_sources",
                category = AuditCategory.APPS,
                status = AuditStatus.OK,
                titleRes = R.string.audit_unknown_sources_title,
                detailRes = R.string.audit_unknown_sources_off
            )
        }
    }

    /** Несистемные приложения, которым сейчас разрешена установка APK. */
    private fun appsAllowedToInstallApks(apps: List<ApplicationInfo>): List<String> {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return emptyList()
        return apps.asSequence()
            .filterNot { it.isSystem }
            .filterNot { it.packageName == context.packageName }
            .filter { appInfo ->
                val mode = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES,
                            appInfo.uid,
                            appInfo.packageName
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES,
                            appInfo.uid,
                            appInfo.packageName
                        )
                    }
                } catch (_: Exception) {
                    AppOpsManager.MODE_DEFAULT
                }
                mode == AppOpsManager.MODE_ALLOWED
            }
            .map { it.packageName }
            .toList()
    }

    private fun checkAdb(): AuditItem {
        val usb = readGlobalFlag(Settings.Global.ADB_ENABLED, default = 0) == 1
        val wifi = readGlobalFlag("adb_wifi_enabled", default = 0) == 1
        val enabled = usb || wifi
        return AuditItem(
            id = "adb",
            category = AuditCategory.SECURITY,
            status = if (enabled) AuditStatus.WARNING else AuditStatus.OK,
            titleRes = R.string.audit_adb_title,
            detailRes = if (enabled) R.string.audit_adb_on else R.string.audit_adb_off,
            recommendationRes = if (enabled) R.string.audit_adb_rec else null
        )
    }

    private fun checkDeveloperOptions(): AuditItem {
        val enabled = readGlobalFlag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, default = 0) == 1
        return AuditItem(
            id = "developer_options",
            category = AuditCategory.SECURITY,
            status = if (enabled) AuditStatus.INFO else AuditStatus.OK,
            titleRes = R.string.audit_dev_options_title,
            detailRes = if (enabled) R.string.audit_dev_options_on else R.string.audit_dev_options_off
        )
    }

    private fun checkSecurityPatch(): AuditItem {
        val patch = Build.VERSION.SECURITY_PATCH.orEmpty()
        val months = monthsSincePatch(patch)
        return when {
            patch.isBlank() || months == null -> AuditItem(
                id = "security_patch",
                category = AuditCategory.SYSTEM,
                status = AuditStatus.INFO,
                titleRes = R.string.audit_patch_title,
                detailRes = R.string.audit_patch_unknown
            )
            months >= 12 -> AuditItem(
                id = "security_patch",
                category = AuditCategory.SYSTEM,
                status = AuditStatus.CRITICAL,
                titleRes = R.string.audit_patch_title,
                detailRes = R.string.audit_patch_detail,
                detailArgs = listOf(patch, months.toString()),
                recommendationRes = R.string.audit_patch_rec
            )
            months >= 3 -> AuditItem(
                id = "security_patch",
                category = AuditCategory.SYSTEM,
                status = AuditStatus.WARNING,
                titleRes = R.string.audit_patch_title,
                detailRes = R.string.audit_patch_detail,
                detailArgs = listOf(patch, months.toString()),
                recommendationRes = R.string.audit_patch_rec
            )
            else -> AuditItem(
                id = "security_patch",
                category = AuditCategory.SYSTEM,
                status = AuditStatus.OK,
                titleRes = R.string.audit_patch_title,
                detailRes = R.string.audit_patch_detail,
                detailArgs = listOf(patch, months.toString())
            )
        }
    }

    private fun checkPackageVerifier(): AuditItem {
        // На современных версиях настройка может отсутствовать — тогда считаем,
        // что проверка приложений включена (значение по умолчанию).
        val enabled = readGlobalFlag("package_verifier_enable", default = 1) == 1
        return AuditItem(
            id = "package_verifier",
            category = AuditCategory.APPS,
            status = if (enabled) AuditStatus.OK else AuditStatus.WARNING,
            titleRes = R.string.audit_verifier_title,
            detailRes = if (enabled) R.string.audit_verifier_on else R.string.audit_verifier_off,
            recommendationRes = if (enabled) null else R.string.audit_verifier_rec
        )
    }

    // ----------------------------------------------------------------- network

    private fun checkVpn(): AuditItem {
        val vpnActive = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val capabilities = network?.let { cm?.getNetworkCapabilities(it) }
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) {
            false
        }
        return AuditItem(
            id = "vpn",
            category = AuditCategory.NETWORK,
            status = AuditStatus.INFO,
            titleRes = R.string.audit_vpn_title,
            detailRes = if (vpnActive) R.string.audit_vpn_on else R.string.audit_vpn_off
        )
    }

    // ----------------------------------------------------------------- storage

    private fun checkFreeSpace(): AuditItem {
        val freeGb = try {
            StatFs(context.filesDir.absolutePath).availableBytes.toDouble() / GIGABYTE
        } catch (_: Exception) {
            -1.0
        }
        val text = if (freeGb < 0) "-" else String.format(Locale.getDefault(), "%.1f", freeGb)
        val status = when {
            freeGb < 0 -> AuditStatus.INFO
            freeGb < 0.5 -> AuditStatus.CRITICAL
            freeGb < 2.0 -> AuditStatus.WARNING
            else -> AuditStatus.OK
        }
        return AuditItem(
            id = "free_space",
            category = AuditCategory.STORAGE,
            status = status,
            titleRes = R.string.audit_storage_title,
            detailRes = R.string.audit_storage_detail,
            detailArgs = listOf(text),
            recommendationRes = if (status == AuditStatus.OK || status == AuditStatus.INFO) {
                null
            } else {
                R.string.audit_storage_rec
            }
        )
    }

    // -------------------------------------------------------------------- apps

    private fun checkInstalledApps(apps: List<ApplicationInfo>): AuditItem {
        val count = apps.size
        return AuditItem(
            id = "installed_apps",
            category = AuditCategory.APPS,
            status = AuditStatus.INFO,
            titleRes = R.string.audit_apps_count_title,
            detailRes = R.string.audit_apps_count_detail,
            detailArgs = listOf(count.toString())
        )
    }

    private fun checkOutdatedApps(apps: List<ApplicationInfo>): AuditItem {
        val outdated = apps.count { !it.isSystem && it.targetSdkVersion < MIN_TARGET_SDK }
        return AuditItem(
            id = "outdated_apps",
            category = AuditCategory.APPS,
            status = if (outdated > 0) AuditStatus.WARNING else AuditStatus.OK,
            titleRes = R.string.audit_outdated_title,
            detailRes = R.string.audit_outdated_detail,
            detailArgs = listOf(outdated.toString()),
            recommendationRes = if (outdated > 0) R.string.audit_outdated_rec else null
        )
    }

    private fun checkDebuggableApps(apps: List<ApplicationInfo>): AuditItem {
        val debuggable = apps.count {
            !it.isSystem &&
                (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                it.packageName != context.packageName
        }
        return AuditItem(
            id = "debuggable_apps",
            category = AuditCategory.APPS,
            status = if (debuggable > 0) AuditStatus.WARNING else AuditStatus.OK,
            titleRes = R.string.audit_debuggable_title,
            detailRes = R.string.audit_debuggable_detail,
            detailArgs = listOf(debuggable.toString()),
            recommendationRes = if (debuggable > 0) R.string.audit_debuggable_rec else null
        )
    }

    /**
     * Службы доступности.
     *
     * Считаются только сторонние службы: TalkBack, Switch Access и прочие системные
     * включены на многих устройствах и не являются находкой (см. [DeviceHeuristics]).
     */
    private fun checkAccessibilityServices(): AuditItem {
        val enabledSetting = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        }
        val untrusted = DeviceHeuristics.untrustedAccessibilityPackages(
            enabledSetting,
            context.packageName
        )
        return AuditItem(
            id = "accessibility_services",
            category = AuditCategory.APPS,
            status = if (untrusted.isNotEmpty()) AuditStatus.WARNING else AuditStatus.OK,
            titleRes = R.string.audit_accessibility_title,
            detailRes = R.string.audit_accessibility_detail,
            detailArgs = listOf(untrusted.size.toString()),
            recommendationRes = if (untrusted.isNotEmpty()) R.string.audit_accessibility_rec else null
        )
    }

    // ------------------------------------------------------------- permissions

    private fun checkNotifications(): AuditItem {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return AuditItem(
            id = "notifications",
            category = AuditCategory.SYSTEM,
            status = if (enabled) AuditStatus.OK else AuditStatus.WARNING,
            titleRes = R.string.audit_notifications_title,
            detailRes = if (enabled) R.string.audit_notifications_on else R.string.audit_notifications_off,
            recommendationRes = if (enabled) null else R.string.audit_notifications_rec
        )
    }

    private fun checkBatteryOptimization(): AuditItem {
        val exempt = try {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Exception) {
            true
        }
        return AuditItem(
            id = "battery_optimization",
            category = AuditCategory.SYSTEM,
            status = if (exempt) AuditStatus.OK else AuditStatus.WARNING,
            titleRes = R.string.audit_battery_title,
            detailRes = if (exempt) R.string.audit_battery_ok else R.string.audit_battery_warning,
            recommendationRes = if (exempt) null else R.string.audit_battery_rec
        )
    }

    private fun checkCorePermissions(): AuditItem {
        val required = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        val coreBroken = missing.any {
            it == Manifest.permission.READ_PHONE_STATE ||
                it == Manifest.permission.SEND_SMS ||
                it == Manifest.permission.RECEIVE_SMS
        }
        val status = when {
            missing.isEmpty() -> AuditStatus.OK
            coreBroken -> AuditStatus.CRITICAL
            else -> AuditStatus.WARNING
        }
        return AuditItem(
            id = "core_permissions",
            category = AuditCategory.SYSTEM,
            status = status,
            titleRes = R.string.audit_core_permissions_title,
            detailRes = if (missing.isEmpty()) {
                R.string.audit_core_permissions_ok
            } else {
                R.string.audit_core_permissions_missing
            },
            detailArgs = if (missing.isEmpty()) {
                emptyList()
            } else {
                listOf(missing.size.toString(), required.size.toString())
            },
            recommendationRes = if (missing.isEmpty()) null else R.string.audit_core_permissions_rec
        )
    }

    /**
     * Администраторы устройства.
     *
     * Раньше тревога поднималась только при `admins > 1`: один посторонний администратор
     * (типичный способ закрепиться в системе) выглядел как «всё в порядке», а собственный
     * Device Admin PhoneGuard попадал в счётчик. Теперь считаются чужие администраторы.
     */
    private fun checkDeviceAdmins(): AuditItem {
        val admins = try {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
                ?.activeAdmins
                ?.count { it.packageName != context.packageName }
                ?: 0
        } catch (_: Exception) {
            0
        }
        val suspicious = admins > 0
        return AuditItem(
            id = "device_admins",
            category = AuditCategory.SECURITY,
            status = if (suspicious) AuditStatus.WARNING else AuditStatus.OK,
            titleRes = R.string.audit_device_admins_title,
            detailRes = R.string.audit_device_admins_detail,
            detailArgs = listOf(admins.toString()),
            recommendationRes = if (suspicious) R.string.audit_device_admins_rec else null
        )
    }

    // ------------------------------------------------------------------ system

    private fun checkOsVersion(): AuditItem = AuditItem(
        id = "os_version",
        category = AuditCategory.SYSTEM,
        status = AuditStatus.INFO,
        titleRes = R.string.audit_os_version_title,
        detailRes = R.string.audit_os_version_detail,
        detailArgs = listOf(Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT.toString())
    )

    private fun checkDeviceModel(): AuditItem = AuditItem(
        id = "device_model",
        category = AuditCategory.SYSTEM,
        status = AuditStatus.INFO,
        titleRes = R.string.audit_device_model_title,
        detailRes = R.string.audit_device_model_detail,
        detailArgs = listOf("${Build.MANUFACTURER} ${Build.MODEL}".trim())
    )

    // ----------------------------------------------------------------- helpers

    private fun readGlobalFlag(key: String, default: Int): Int = try {
        Settings.Global.getInt(context.contentResolver, key, default)
    } catch (_: Exception) {
        default
    }

    private fun installedApps(): List<ApplicationInfo> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private val ApplicationInfo.isSystem: Boolean
        get() = (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    /**
     * Сколько полных месяцев прошло с даты патча (`yyyy-MM-dd`).
     *
     * `isLenient = false` сам по себе не спасает: `parse()` принимает строку с «хвостом»
     * (`2024-01-01-junk`). Поэтому проверяется, что разобрана вся строка целиком — так же,
     * как в `SecurityPatchCheck`.
     */
    private fun monthsSincePatch(patch: String): Int? {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.isLenient = false
        val position = ParsePosition(0)
        val date = try {
            format.parse(patch, position) ?: return null
        } catch (_: Exception) {
            return null
        }
        if (position.index != patch.length) return null
        val diff = System.currentTimeMillis() - date.time
        return if (diff < 0) 0 else (diff / MILLIS_PER_MONTH).toInt()
    }

    companion object {
        private const val GIGABYTE = 1024.0 * 1024.0 * 1024.0
        private const val MILLIS_PER_MONTH = 30L * 24 * 60 * 60 * 1000
        /** Ниже этого уровня targetSdk приложение уже не получает актуальные защиты ОС. */
        const val MIN_TARGET_SDK = 30
    }
}
