package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.phoneguard.malware.ApkSignatureScanner
import com.phoneguard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка подписей установленных APK (интеграция с apk-parser).
 *
 * Ищет признаки пересборки/подмены приложения:
 *  - подпись debug-ключом у приложения, установленного из магазина;
 *  - слабый алгоритм подписи (MD5/SHA-1);
 *  - истёкший сертификат;
 *  - APK изменён после установки (инъекция кода в файл).
 *
 * ВАЖНО: все выводы — эвристики. Разработчики иногда подписывают приложения
 * старыми ключами, поэтому находки уровня INFO/WARNING не доказывают вредоносность.
 */
@Singleton
class ApkSignatureCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apkSignatureScanner: ApkSignatureScanner
) {

    data class SignatureFinding(
        val packageName: String,
        val appName: String,
        val severity: Severity,
        val reason: String
    )

    enum class Severity { CRITICAL, WARNING, INFO }

    data class ApkSignatureResult(
        val inspectedCount: Int,
        val findings: List<SignatureFinding>,
        val details: List<String>
    ) {
        val hasCritical: Boolean get() = findings.any { it.severity == Severity.CRITICAL }
    }

    suspend fun performCheck(): ApkSignatureResult = withContext(Dispatchers.IO) {
        val findings = mutableListOf<SignatureFinding>()
        val details = mutableListOf<String>()

        val packageManager = context.packageManager
        val candidates = try {
            installedThirdPartyPackages(packageManager)
        } catch (_: Exception) {
            emptyList()
        }

        // Бюджет времени — по монотонным часам: перевод системного времени
        // не должен обрывать проверку раньше срока (или растягивать её).
        val deadline = SystemClock.elapsedRealtime() + TIME_BUDGET_MS
        var inspected = 0

        // `for` вместо `forEach`: по исчерпании бюджета нужен `break`, а `return@forEach`
        // пропускал только текущий элемент и продолжал крутиться по всем пакетам.
        for (packageInfo in candidates) {
            if (!ApkSignaturePolicy.withinBudget(SystemClock.elapsedRealtime(), deadline)) break
            val packageName = packageInfo.packageName
            if (packageName == context.packageName) continue

            val sourceDir = packageInfo.applicationInfo?.sourceDir ?: continue
            val signature = apkSignatureScanner.inspectApk(
                packageName = packageName,
                apkFile = java.io.File(sourceDir)
            )
            if (signature.error != null && !signature.hasCertificates) continue
            inspected++

            val appName = try {
                // Раньше здесь стояло `?: pkg` — необъявленная переменная (ошибка компиляции).
                packageInfo.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() }
                    ?: packageName
            } catch (_: Exception) {
                packageName
            }

            // Решение «находка или нет» принимает чистый ApkSignaturePolicy; здесь
            // только сбор данных с устройства и строки ресурсов.
            val state = ApkSignaturePolicy.State(
                installedFromDebugKey = signature.isInstalledFromDebugKey,
                debuggable = isDebuggable(packageInfo),
                installedFromStore = ApkSignaturePolicy.isStoreInstaller(
                    installSource(packageManager, packageName)
                ),
                weakSignature = signature.hasWeakSignature,
                certificateExpired = signature.isCertificateExpired,
                modifiedAfterInstall = signature.apkModifiedAfterInstall
            )
            val algorithms = signature.certificates
                .joinToString { it.signatureAlgorithm }
                .take(MAX_ALGORITHM_TEXT)

            ApkSignaturePolicy.assess(state).forEach { verdict ->
                findings += SignatureFinding(
                    packageName = packageName,
                    appName = appName,
                    // Наборы Severity совпадают по именам, поэтому `valueOf` безопасен.
                    severity = Severity.valueOf(verdict.severity.name),
                    reason = when (verdict.reason) {
                        ApkSignaturePolicy.Reason.DEBUG_KEY_FROM_STORE ->
                            context.getString(R.string.apk_sig_reason_debug_store)
                        ApkSignaturePolicy.Reason.DEBUG_KEY ->
                            context.getString(R.string.apk_sig_reason_debug)
                        ApkSignaturePolicy.Reason.WEAK_ALGORITHM ->
                            context.getString(R.string.apk_sig_reason_weak, algorithms)
                        ApkSignaturePolicy.Reason.EXPIRED_CERTIFICATE ->
                            context.getString(R.string.apk_sig_reason_expired)
                        ApkSignaturePolicy.Reason.MODIFIED_AFTER_INSTALL ->
                            context.getString(R.string.apk_sig_reason_modified)
                    }
                )
            }
        }

        details += context.getString(R.string.apk_sig_checked_count, inspected)
        details += if (findings.isEmpty()) {
            context.getString(R.string.apk_sig_none_found)
        } else {
            context.getString(R.string.apk_sig_problems_found, findings.size)
        }

        ApkSignatureResult(
            inspectedCount = inspected,
            findings = findings,
            details = details
        )
    }

    private fun installedThirdPartyPackages(packageManager: PackageManager): List<android.content.pm.PackageInfo> {
        val flags = PackageManager.GET_PERMISSIONS
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(flags)
        }
        return packages.filter { info ->
            val appInfo = info.applicationInfo ?: return@filter false
            val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            !isSystem || isUpdatedSystem
        }
    }

    private fun isDebuggable(packageInfo: android.content.pm.PackageInfo): Boolean =
        (packageInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun installSource(packageManager: PackageManager, packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Бюджет времени на проверку подписей, чтобы полный скан не «зависал». */
        private const val TIME_BUDGET_MS = 15_000L

        /** Ограничение длины списка алгоритмов в тексте находки. */
        private const val MAX_ALGORITHM_TEXT = 80
    }
}
