package com.phoneguard.fullscan

import android.content.Context
import com.google.gson.Gson
import com.phoneguard.R
import com.phoneguard.data.local.ScanHistoryDao
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.data.repository.PrivacyScannerRepository
import com.phoneguard.data.repository.SpywareCheckRepository
import com.phoneguard.fullscan.checks.*
import com.phoneguard.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FullScanOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootDetectionCheck: RootDetectionCheck,
    private val systemIntegrityCheck: SystemIntegrityCheck,
    private val fileSystemScanCheck: FileSystemScanCheck,
    private val developerOptionsCheck: DeveloperOptionsCheck,
    private val securityPatchCheck: SecurityPatchCheck,
    private val accessibilityServiceCheck: AccessibilityServiceCheck,
    private val overlayAttackCheck: OverlayAttackCheck,
    private val permissionAbuseCheck: PermissionAbuseCheck,
    private val hiddenAppsCheck: HiddenAppsCheck,
    private val deviceAdminAbuseCheck: DeviceAdminAbuseCheck,
    private val privacyScannerRepository: PrivacyScannerRepository,
    private val spywareCheckRepository: SpywareCheckRepository,
    private val firewallRepository: FirewallRepository,
    private val scanHistoryDao: ScanHistoryDao,
    private val phishingUrlChecker: PhishingUrlChecker,
    private val apkSignatureCheck: ApkSignatureCheck,
    private val deviceAuditChecker: com.phoneguard.deviceaudit.DeviceAuditChecker
) {
    companion object {
        /**
         * Количество шагов полного сканирования.
         *
         * Считается один раз здесь: раньше значение использовалось в `runFullScan`
         * без объявления (ошибка компиляции), а «магическое число» в двух местах
         * разъезжалось при добавлении проверки.
         */
        const val TOTAL_STEPS = 16

        /** Проверки устройства, которые полный скан выполняет сам (без дублей). */
        private val AUDIT_IDS_ALREADY_COVERED = setOf(
            "adb",
            "developer_options",
            "security_patch"
        )
    }
    private val gson = Gson()

    sealed class FullScanState {
        data class Progress(
            val step: Int,
            val totalSteps: Int,
            val stage: ScanStage,
            val isFinished: Boolean = false
        ) : FullScanState()
        data class Result(val report: FullScanReport, val scanHistoryId: Long) : FullScanState()
    }

    /**
     * Флаг «скан уже идёт».
     *
     * Раньше это был обычный `Boolean`, и проверка `if (isScanning) … isScanning = true`
     * не была атомарной: полный скан из UI и периодический воркер могли стартовать
     * одновременно (двойная нагрузка, дубли записей в истории).
     *
     * `AtomicBoolean`, а не `@Volatile var`: `@Volatile` к `val` применять нельзя
     * (ошибка компиляции), а атомарность нужна именно для пары «проверить и
     * выставить» — её даёт `compareAndSet`.
     */
    private val isScanning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun buildReport(
        root: RootDetectionCheck.RootCheckResult,
        integrity: SystemIntegrityCheck.IntegrityResult,
        fileScan: FileSystemScanCheck.FileScanResult,
        devOptions: DeveloperOptionsCheck.DevOptionsResult,
        patch: SecurityPatchCheck.PatchResult,
        accessibility: AccessibilityServiceCheck.AccessibilityResult,
        overlays: OverlayAttackCheck.OverlayResult,
        permissionAbuse: PermissionAbuseCheck.PermissionAbuseResult,
        hiddenApps: HiddenAppsCheck.HiddenAppsResult,
        deviceAdmins: DeviceAdminAbuseCheck.DeviceAdminResult,
        privacyApps: List<com.phoneguard.model.PrivacyApp>,
        spywareApps: List<com.phoneguard.model.SpywareApp>,
        firewallRules: List<com.phoneguard.model.FirewallRule>,
        firewallLogs: List<com.phoneguard.model.FirewallLog>,
        phishingResult: PhishingUrlChecker.PhishingResult,
        apkSignatures: ApkSignatureCheck.ApkSignatureResult,
        deviceAudit: com.phoneguard.deviceaudit.DeviceAuditReport
    ): FullScanReport {
        val issues = mutableListOf<ScanIssue>()

        /**
         * Тексты находок берутся из ресурсов.
         *
         * Раньше весь отчёт был захардкожен по-русски: в английской и немецкой локали
         * пользователь получал русские заголовки, описания и рекомендации — и в
         * приложении, и в экспортированном PDF.
         */
        fun text(resId: Int, vararg args: Any): String = context.getString(resId, *args)

        if (root.isRooted) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = text(R.string.scan_issue_root_title),
                    description = text(R.string.scan_issue_root_desc, (root.confidence * 100).toInt()),
                    recommendation = text(R.string.scan_issue_root_rec),
                    isHeuristic = true
                )
            )
        }
        if (integrity.isCompromised) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = text(R.string.scan_issue_integrity_title),
                    description = text(R.string.scan_issue_integrity_desc),
                    recommendation = text(R.string.scan_issue_integrity_rec),
                    isHeuristic = true
                )
            )
        }
        fileScan.suspiciousFiles.forEach { file ->
            issues.add(
                ScanIssue(
                    severity = when (file.severity) {
                        3 -> IssueSeverity.CRITICAL
                        2 -> IssueSeverity.WARNING
                        else -> IssueSeverity.INFO
                    },
                    title = text(R.string.scan_issue_file_title, file.path.takeLast(60)),
                    description = file.reason,
                    recommendation = text(R.string.scan_issue_file_rec),
                    isHeuristic = true
                )
            )
        }

        if (accessibility.suspiciousServices.isNotEmpty()) {
            val names = accessibility.suspiciousServices.joinToString(", ") { it.appName }
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = text(
                        R.string.scan_issue_accessibility_title,
                        accessibility.suspiciousServices.size
                    ),
                    description = text(R.string.scan_issue_accessibility_desc, names),
                    recommendation = text(R.string.scan_issue_accessibility_rec)
                )
            )
        }

        if (overlays.hasDangerousOverlays) {
            val names = overlays.appsWithOverlay.joinToString(", ") { it.appName }
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = text(R.string.scan_issue_overlay_title),
                    description = text(R.string.scan_issue_overlay_desc, names),
                    recommendation = text(R.string.scan_issue_overlay_rec)
                )
            )
        }

        if (permissionAbuse.riskyApps.isNotEmpty()) {
            val critical = permissionAbuse.riskyApps.count {
                it.riskLevel == PermissionAbuseCheck.RiskLevel.CRITICAL
            }
            val high = permissionAbuse.riskyApps.count {
                it.riskLevel == PermissionAbuseCheck.RiskLevel.HIGH
            }
            val examples = permissionAbuse.riskyApps
                .take(3)
                .joinToString("; ") { "${it.appName}: ${it.explanation}" }
            issues.add(
                ScanIssue(
                    severity = if (critical > 0) IssueSeverity.CRITICAL else IssueSeverity.WARNING,
                    title = text(
                        R.string.scan_issue_permissions_title,
                        permissionAbuse.riskyApps.size,
                        critical,
                        high
                    ),
                    description = text(R.string.scan_issue_permissions_desc, examples),
                    recommendation = text(R.string.scan_issue_privacy_rec)
                )
            )
        }

        if (hiddenApps.totalCount > 0) {
            // Признак «подозрительное имя» берём из структуры, а не из текста причины:
            // раньше сравнение шло с русской строкой, и на другом языке фильтр не работал.
            val suspiciousNames = hiddenApps.hiddenApps.filter { it.isSuspiciousName }
            if (suspiciousNames.isNotEmpty()) {
                val names = suspiciousNames.joinToString(", ") { "${it.appName} (${it.packageName})" }
                issues.add(
                    ScanIssue(
                        severity = IssueSeverity.CRITICAL,
                        title = text(R.string.scan_issue_hidden_suspicious_title, suspiciousNames.size),
                        description = text(R.string.scan_issue_hidden_suspicious_desc, names),
                        recommendation = text(R.string.scan_issue_hidden_rec)
                    )
                )
            } else {
                val names = hiddenApps.hiddenApps.take(3).joinToString(", ") { it.appName }
                issues.add(
                    ScanIssue(
                        severity = IssueSeverity.WARNING,
                        title = text(R.string.scan_issue_hidden_title, hiddenApps.totalCount),
                        description = text(R.string.scan_issue_hidden_desc, names),
                        recommendation = text(R.string.scan_issue_hidden_check_rec)
                    )
                )
            }
        }

        if (deviceAdmins.hasSuspiciousAdmins) {
            val names = deviceAdmins.activeAdmins.joinToString(", ") { it.appName }
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = text(R.string.scan_issue_admins_title, deviceAdmins.activeAdmins.size),
                    description = text(R.string.scan_issue_admins_desc, names),
                    recommendation = text(R.string.scan_issue_admins_rec)
                )
            )
        }

        val appsWithRiskyPermissions = privacyApps.filter { it.hasExcessivePermissions }
        if (appsWithRiskyPermissions.isNotEmpty()) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.WARNING,
                    title = text(R.string.scan_issue_excessive_title, appsWithRiskyPermissions.size),
                    description = text(R.string.scan_issue_excessive_desc),
                    recommendation = text(R.string.scan_issue_privacy_rec)
                )
            )
        }

        val suspiciousSpywareApps = spywareApps.filter { it.indicators.isNotEmpty() }
        if (suspiciousSpywareApps.isNotEmpty()) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.WARNING,
                    title = text(R.string.scan_issue_spyware_title, suspiciousSpywareApps.size),
                    description = text(R.string.scan_issue_spyware_desc),
                    recommendation = text(R.string.scan_issue_spyware_rec),
                    isHeuristic = true
                )
            )
        }

        val blockedRecent = firewallLogs.take(20)
        if (blockedRecent.isNotEmpty()) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.INFO,
                    title = text(R.string.scan_issue_firewall_blocks_title, blockedRecent.size),
                    description = text(R.string.scan_issue_firewall_blocks_desc),
                    recommendation = text(R.string.scan_issue_firewall_blocks_rec),
                    isHeuristic = true
                )
            )
        }
        if (firewallRules.isEmpty()) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.INFO,
                    title = text(R.string.scan_issue_firewall_empty_title),
                    description = text(R.string.scan_issue_firewall_empty_desc),
                    recommendation = text(R.string.scan_issue_firewall_empty_rec)
                )
            )
        }

        val criticalSignatureFindings =
            apkSignatures.findings.filter { it.severity == ApkSignatureCheck.Severity.CRITICAL }
        if (criticalSignatureFindings.isNotEmpty()) {
            val names = criticalSignatureFindings.take(3).joinToString(", ") { it.appName }
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = text(R.string.scan_issue_signature_debug_title, criticalSignatureFindings.size),
                    description = text(R.string.scan_issue_signature_debug_desc, names),
                    recommendation = text(R.string.scan_issue_signature_remove_rec)
                )
            )
        }
        val warningSignatureFindings =
            apkSignatures.findings.filter { it.severity == ApkSignatureCheck.Severity.WARNING }
        if (warningSignatureFindings.isNotEmpty()) {
            val details = warningSignatureFindings.take(3)
                .joinToString("; ") { "${it.appName}: ${it.reason}" }
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.WARNING,
                    title = text(R.string.scan_issue_signature_warning_title, warningSignatureFindings.size),
                    description = text(R.string.scan_issue_signature_warning_desc, details),
                    recommendation = text(R.string.scan_issue_signature_verify_rec),
                    isHeuristic = true
                )
            )
        }
        val infoSignatureFindings =
            apkSignatures.findings.filter { it.severity == ApkSignatureCheck.Severity.INFO }
        if (infoSignatureFindings.isNotEmpty()) {
            val names = infoSignatureFindings.take(3).joinToString(", ") { it.appName }
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.INFO,
                    title = text(R.string.scan_issue_signature_expired_title, infoSignatureFindings.size),
                    description = text(R.string.scan_issue_signature_expired_desc, names),
                    recommendation = text(R.string.scan_issue_signature_update_rec),
                    isHeuristic = true
                )
            )
        }

        if (phishingResult.suspiciousMessages.isNotEmpty()) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.WARNING,
                    title = text(R.string.scan_issue_phishing_title, phishingResult.suspiciousMessages.size),
                    description = text(R.string.scan_issue_phishing_desc),
                    recommendation = text(R.string.scan_issue_phishing_rec),
                    isHeuristic = true
                )
            )
        }

        if (devOptions.isUsbDebuggingEnabled) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.WARNING,
                    title = text(R.string.scan_issue_usb_debug_title),
                    description = text(R.string.scan_issue_usb_debug_desc),
                    recommendation = text(R.string.scan_issue_usb_debug_rec),
                    isHeuristic = true
                )
            )
        }
        if (devOptions.isDeveloperOptionsEnabled) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.INFO,
                    title = text(R.string.scan_issue_dev_options_title),
                    description = text(R.string.scan_issue_dev_options_desc),
                    recommendation = text(R.string.scan_issue_dev_options_rec),
                    isHeuristic = true
                )
            )
        }

        if (patch.isCritical) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = text(R.string.scan_issue_patch_title, patch.monthsOutdated),
                    description = text(R.string.scan_issue_patch_desc, patchDateLabel(patch.patchDate)),
                    recommendation = text(R.string.scan_issue_patch_rec)
                )
            )
        } else if (patch.monthsOutdated >= 3) {
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.WARNING,
                    title = text(R.string.scan_issue_patch_title, patch.monthsOutdated),
                    description = text(R.string.scan_issue_patch_desc, patchDateLabel(patch.patchDate)),
                    recommendation = text(R.string.scan_issue_patch_rec)
                )
            )
        }

        // Чек всего телефона: проблемы устройства (блокировка экрана, шифрование,
        // свободное место, разрешения…) попадают в общий отчёт.
        // Проверки, которые уже есть в самом скане (ADB, режим разработчика, патч),
        // пропускаем, иначе одна и та же проблема штрафует оценку дважды.
        val auditItemsToReport = deviceAudit.items.filter { it.id !in AUDIT_IDS_ALREADY_COVERED }
        auditItemsToReport
            .filter { it.status == com.phoneguard.deviceaudit.AuditStatus.CRITICAL }
            .forEach { item ->
                issues.add(ScanIssue(
                    severity = IssueSeverity.CRITICAL,
                    title = deviceAuditTitle(item),
                    description = deviceAuditDetail(item),
                    recommendation = item.recommendationRes?.let { deviceAuditText(it) }.orEmpty(),
                    isHeuristic = false
                ))
            }
        auditItemsToReport
            .filter { it.status == com.phoneguard.deviceaudit.AuditStatus.WARNING }
            .forEach { item ->
                issues.add(ScanIssue(
                    severity = IssueSeverity.WARNING,
                    title = deviceAuditTitle(item),
                    description = deviceAuditDetail(item),
                    recommendation = item.recommendationRes?.let { deviceAuditText(it) }.orEmpty(),
                    isHeuristic = true
                ))
            }

        if (issues.isEmpty()) {
            // Раньше текст был захардкожен по-русски и попадал в отчёт на любом языке.
            issues.add(
                ScanIssue(
                    severity = IssueSeverity.INFO,
                    title = deviceAuditText(R.string.scan_no_issues_title),
                    description = deviceAuditText(R.string.scan_no_issues_description),
                    recommendation = deviceAuditText(R.string.scan_no_issues_recommendation)
                )
            )
        }
        val criticalCount = issues.count { it.severity == IssueSeverity.CRITICAL }
        val warningCount = issues.count { it.severity == IssueSeverity.WARNING }
        val infoCount = issues.count { it.severity == IssueSeverity.INFO }
        val riskScore = (100 - criticalCount * 30 - warningCount * 10).coerceIn(0, 100)
        return FullScanReport(riskScore, issues, criticalCount, warningCount, infoCount)
    }

    /**
     * Подпись даты патча для отчёта.
     *
     * `SecurityPatchCheck` остаётся чистой (без `Context`) и возвращает пустую
     * строку, если производитель не указал дату, — раньше в отчёт подставлялось
     * русское «неизвестно» в любой локали.
     */
    private fun patchDateLabel(patchDate: String): String =
        if (patchDate.isBlank()) deviceAuditText(R.string.patch_date_unknown) else patchDate

    /** Заголовок проверки устройства в терминах ScanIssue (текст уже локализован). */
    private fun deviceAuditTitle(item: com.phoneguard.deviceaudit.AuditItem): String =
        deviceAuditText(item.titleRes)

    private fun deviceAuditDetail(item: com.phoneguard.deviceaudit.AuditItem): String =
        if (item.detailArgs.isEmpty()) {
            deviceAuditText(item.detailRes)
        } else {
            deviceAuditText(item.detailRes, *item.detailArgs.toTypedArray())
        }

    /**
     * Строка из ресурсов. Прокси через `DeviceAuditChecker.getString` больше не нужен:
     * у оркестратора есть свой `Context`, и обёртка с `try/catch` лишь прятала бы
     * реальные ошибки формата строк.
     */
    private fun deviceAuditText(resId: Int, vararg args: Any): String =
        context.getString(resId, *args)

    private suspend fun saveScanHistory(report: FullScanReport): Long {
        val totalIssues = report.criticalCount + report.warningCount + report.infoCount
        return scanHistoryDao.insertScan(ScanHistory(
            riskScore = report.riskScore, issuesFound = totalIssues,
            reportJson = gson.toJson(report)))
    }

    suspend fun getLatestScan(): ScanHistory? = scanHistoryDao.getLatestScan()
    fun getAllScans() = scanHistoryDao.getAllScans()

    suspend fun getScanById(id: Long): ScanHistory? = scanHistoryDao.getScanById(id)

    suspend fun getLatestScanSummary(): Pair<Int?, String?> {
        val latest = scanHistoryDao.getLatestScan() ?: return null to null
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        return latest.riskScore to sdf.format(java.util.Date(latest.timestamp))
    }

    fun runFullScan(): Flow<FullScanState> = flow {
        if (!isScanning.compareAndSet(false, true)) {
            emit(FullScanState.Progress(0, TOTAL_STEPS, ScanStage.ALREADY_RUNNING, isFinished = true))
            return@flow
        }
        val totalSteps = TOTAL_STEPS
        try {
            emit(FullScanState.Progress(1, totalSteps, ScanStage.ROOT))
            val rootResult = withContext(Dispatchers.IO) { rootDetectionCheck.performCheck() }

            emit(FullScanState.Progress(2, totalSteps, ScanStage.INTEGRITY))
            val integrityResult = withContext(Dispatchers.IO) { systemIntegrityCheck.performCheck() }

            emit(FullScanState.Progress(3, totalSteps, ScanStage.FILE_SYSTEM))
            val fileScanResult = withContext(Dispatchers.IO) { fileSystemScanCheck.performScan() }

            emit(FullScanState.Progress(4, totalSteps, ScanStage.DEVELOPER_OPTIONS))
            val devOptionsResult = withContext(Dispatchers.IO) { developerOptionsCheck.performCheck() }

            emit(FullScanState.Progress(5, totalSteps, ScanStage.SECURITY_PATCH))
            val patchResult = withContext(Dispatchers.IO) { securityPatchCheck.performCheck() }

            emit(FullScanState.Progress(6, totalSteps, ScanStage.ACCESSIBILITY))
            val accessibilityResult = withContext(Dispatchers.IO) { accessibilityServiceCheck.performCheck() }

            emit(FullScanState.Progress(7, totalSteps, ScanStage.OVERLAYS))
            val overlayResult = withContext(Dispatchers.IO) { overlayAttackCheck.performCheck() }

            emit(FullScanState.Progress(8, totalSteps, ScanStage.PERMISSIONS))
            val permissionAbuseResult = withContext(Dispatchers.IO) { permissionAbuseCheck.performCheck() }

            emit(FullScanState.Progress(9, totalSteps, ScanStage.HIDDEN_APPS))
            val hiddenAppsResult = withContext(Dispatchers.IO) { hiddenAppsCheck.performCheck() }

            emit(FullScanState.Progress(10, totalSteps, ScanStage.DEVICE_ADMINS))
            val deviceAdminResult = withContext(Dispatchers.IO) { deviceAdminAbuseCheck.performCheck() }

            emit(FullScanState.Progress(11, totalSteps, ScanStage.PRIVACY_APPS))
            val privacyApps = withContext(Dispatchers.IO) { privacyScannerRepository.getInstalledApps().first() }

            emit(FullScanState.Progress(12, totalSteps, ScanStage.SPYWARE))
            val spywareApps = withContext(Dispatchers.IO) { spywareCheckRepository.scanForSpyware().first() }

            emit(FullScanState.Progress(13, totalSteps, ScanStage.FIREWALL))
            // Два запроса к БД параллельно: раньше `async` создавался внутри
            // `withContext`, то есть фактически выполнялся последовательно.
            val (firewallRules, firewallLogs) = coroutineScope {
                val rulesDeferred = async(Dispatchers.IO) { firewallRepository.allRules.first() }
                val logsDeferred = async(Dispatchers.IO) { firewallRepository.allLogs.first() }
                rulesDeferred.await() to logsDeferred.await()
            }

            emit(FullScanState.Progress(14, totalSteps, ScanStage.APK_SIGNATURES))
            val apkSignatureResult = withContext(Dispatchers.IO) { apkSignatureCheck.performCheck() }

            emit(FullScanState.Progress(15, totalSteps, ScanStage.PHISHING))
            val phishingResult = withContext(Dispatchers.IO) { phishingUrlChecker.checkRecentMessages() }

            emit(FullScanState.Progress(16, totalSteps, ScanStage.PHONE_CHECK))
            val deviceAudit = withContext(Dispatchers.IO) { deviceAuditChecker.performAudit() }

            val report = buildReport(
                root = rootResult,
                integrity = integrityResult,
                fileScan = fileScanResult,
                devOptions = devOptionsResult,
                patch = patchResult,
                accessibility = accessibilityResult,
                overlays = overlayResult,
                permissionAbuse = permissionAbuseResult,
                hiddenApps = hiddenAppsResult,
                deviceAdmins = deviceAdminResult,
                privacyApps = privacyApps,
                spywareApps = spywareApps,
                firewallRules = firewallRules,
                firewallLogs = firewallLogs,
                phishingResult = phishingResult,
                apkSignatures = apkSignatureResult,
                deviceAudit = deviceAudit
            )
            val historyId = withContext(Dispatchers.IO) { saveScanHistory(report) }
            emit(FullScanState.Progress(totalSteps, totalSteps, ScanStage.FINISHED, isFinished = true))
            emit(FullScanState.Result(report, historyId))
        } finally {
            isScanning.set(false)
        }
    }
}
