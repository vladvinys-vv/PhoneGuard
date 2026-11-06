package com.phoneguard.protect

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Собирает факты для «защиты чувствительных приложений»: кто на телефоне ценен и кто
 * рядом с ним умеет подглядывать.
 *
 * Откуда берутся права (всё — чтение состояния, без разрешений и без root):
 *
 *  * `SYSTEM_ALERT_WINDOW` — `AppOpsManager.checkOpNoThrow` по uid приложения; одно только
 *    наличие разрешения в манифесте ничего не значит, важен режим;
 *  * службы доступности и доступ к уведомлениям — списки компонентов в `Settings.Secure`;
 *  * администраторы устройства — `DevicePolicyManager.activeAdmins`.
 *
 * Ограничения названы честно: список обновляется при открытии экрана, а не в реальном
 * времени (постоянное наблюдение за `Settings.Secure` требует ContentObserver и держит
 * процесс живым ради подсказки), и права у приложения, установленного после открытия
 * экрана, появятся в списке только при следующем заходе.
 */
@Singleton
class SensitiveAppGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    /** Что показать на экране. */
    data class SensitiveAppsSnapshot(
        val protectedApps: List<SensitiveAppPolicy.AppFacts>,
        val suggestions: List<SensitiveAppPolicy.AppFacts>,
        val exposures: List<SensitiveAppPolicy.Exposure>
    ) {
        /** Есть ли что показать вообще: пустой телефон — не ошибка. */
        val isEmpty: Boolean get() = protectedApps.isEmpty() && suggestions.isEmpty()
    }

    /**
     * Снимок состояния: защищённые, предложения и опасные соседи.
     *
     * Имя не `snapshot()`: в проекте уже есть не-suspend `snapshot()` у
     * `FirewallRuleIndex` и `SessionCounters`, и `kotlin_analysis.py` сопоставляет вызовы
     * по простому имени — одинаковое имя у suspend-функции давало восемь ложных
     * «suspend-вызов в обычной функции».
     */
    suspend fun protectionSnapshot(): SensitiveAppsSnapshot = withContext(Dispatchers.IO) {
        val apps = installedApps()
        val protected = preferencesManager.protectedApps.first()
        SensitiveAppsSnapshot(
            protectedApps = SensitiveAppPolicy.protectedApps(protected, apps),
            suggestions = SensitiveAppPolicy.suggestions(apps, protected),
            exposures = SensitiveAppPolicy.exposures(protected, apps)
        )
    }

    /**
     * Переключает защиту одного приложения.
     *
     * @return новый набор или `null`, если переключение отклонено (предел списка) — экран
     *   должен сказать об этом, а не делать вид, что всё сработало
     */
    suspend fun setProtected(packageName: String, isProtected: Boolean): Set<String>? {
        val current = preferencesManager.protectedApps.first()
        val next = SensitiveAppPolicy.toggle(current, packageName, isProtected)
        if (next == current && isProtected && packageName !in current) return null
        preferencesManager.setProtectedApps(next)
        return next
    }

    /** Сколько список уже занимает — для подсказки о пределе. */
    suspend fun protectedCount(): Int = preferencesManager.protectedApps.first().size

    /**
     * Факты об одном пакете — для реакции на установку (`PackageAddedReceiver`).
     *
     * Отдельный путь, а не поиск в `installedApps()`: там перечисление всех приложений с
     * `getLaunchIntentForPackage` (секунды на большом телефоне), а ресивер живёт под
     * `goAsync()` с жёстким лимитом времени. Права всё равно собираются теми же функциями,
     * поэтому «опасный сосед» и экран показывают одинаковый набор прав.
     *
     * @return `null`, если приложение уже удалили или пакет недоступен
     */
    suspend fun factsFor(packageName: String): SensitiveAppPolicy.AppFacts? =
        withContext(Dispatchers.IO) {
            val info = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0)
            }.getOrNull() ?: return@withContext null
            facts(
                info = info,
                overlay = overlayPackages(),
                accessibility = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                notificationAccess = enabledComponents(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS),
                admins = activeAdminPackages()
            )
        }

    private fun installedApps(): List<SensitiveAppPolicy.AppFacts> {
        val packageManager = context.packageManager
        val overlay = overlayPackages()
        val accessibility = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val notificationAccess = enabledComponents(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS)
        val admins = activeAdminPackages()

        return runCatching {
            packageManager.getInstalledApplications(0)
                .asSequence()
                // Только то, что пользователь может запустить: без этого в списке
                // оказываются десятки фоновых сервисов и системных библиотек.
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .filter { it.packageName != context.packageName }
                .map { appInfo -> facts(appInfo, overlay, accessibility, notificationAccess, admins) }
                .sortedWith(compareBy({ it.displayName.lowercase() }, { it.packageName }))
                .toList()
        }.getOrElse { error ->
            Log.w(TAG, "Cannot enumerate installed apps", error)
            emptyList()
        }
    }

    private fun facts(
        info: ApplicationInfo,
        overlay: Set<String>,
        accessibility: Set<String>,
        notificationAccess: Set<String>,
        admins: Set<String>
    ): SensitiveAppPolicy.AppFacts {
        val packageName = info.packageName
        val capabilities = buildSet {
            if (packageName in overlay) add(SensitiveAppPolicy.Capability.OVERLAY)
            if (packageName in accessibility) add(SensitiveAppPolicy.Capability.ACCESSIBILITY)
            if (packageName in notificationAccess) add(SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS)
            if (packageName in admins) add(SensitiveAppPolicy.Capability.DEVICE_ADMIN)
        }
        val label = runCatching {
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("")
        return SensitiveAppPolicy.AppFacts(
            packageName = packageName,
            label = label,
            isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            capabilities = capabilities
        )
    }

    private fun overlayPackages(): Set<String> {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return emptySet()
        val packageManager = context.packageManager
        return runCatching {
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { appInfo ->
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                            appInfo.uid,
                            appInfo.packageName
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                            appInfo.uid,
                            appInfo.packageName
                        )
                    }
                    mode == AppOpsManager.MODE_ALLOWED
                }
                .map { it.packageName }
                .toSet()
        }.getOrElse { error ->
            // Без AppOps список оверлеев пуст: лучше показать меньше, чем выдумать.
            Log.w(TAG, "Cannot read overlay app ops", error)
            emptySet()
        }
    }

    /**
     * Пакеты, у которых включён компонент из `Settings.Secure`-списка.
     *
     * Формат значения — `pkg/cls:pkg/cls`. Пакет без класса тоже встречается, поэтому
     * делим по `/`, а не требуем полного компонента.
     */
    private fun enabledComponents(setting: String): Set<String> {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, setting)
        }.getOrNull() ?: return emptySet()
        return raw.split(':')
            .asSequence()
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun activeAdminPackages(): Set<String> {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptySet()
        return runCatching {
            manager.activeAdmins.orEmpty().mapNotNull { it.packageName }.toSet()
        }.getOrElse { emptySet() }
    }

    private companion object {
        const val TAG = "SensitiveAppGuard"
    }
}
