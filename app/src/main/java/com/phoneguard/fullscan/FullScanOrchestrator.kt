package com.phoneguard.fullscan

import com.google.gson.Gson
import com.phoneguard.data.local.ScanHistoryDao
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.data.repository.PrivacyScannerRepository
import com.phoneguard.data.repository.SpywareCheckRepository
import com.phoneguard.fullscan.checks.*
import com.phoneguard.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FullScanOrchestrator @Inject constructor(
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
    private val phishingUrlChecker: PhishingUrlChecker
) {
    private val gson = Gson()

    sealed class FullScanState {
        data class Progress(val step: Int, val totalSteps: Int, val stageName: String, val isFinished: Boolean = false) : FullScanState()
        data class Result(val report: FullScanReport, val scanHistoryId: Long) : FullScanState()
    }

    @Volatile
    private var isScanning = false

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
        phishingResult: PhishingUrlChecker.PhishingResult
    ): FullScanReport {
        val issues = mutableListOf<ScanIssue>()
        if (root.isRooted) {
            issues.add(ScanIssue(IssueSeverity.CRITICAL, "Обнаружен root-доступ (эвристика)",
                "Признаки root-доступа (уверенность ${(root.confidence * 100).toInt()}%). Данная проверка использует эвристики и не даёт 100% гарантии.",
                "Если root не установлен намеренно — сброс до заводских. Проверьте Magisk/SuperSU.", true))
        }
        if (integrity.isCompromised) {
            issues.add(ScanIssue(IssueSeverity.CRITICAL, "Возможна компрометация системы (эвристика)",
                "Обратите внимание: без root-доступа проверка целостности /system частичная, некоторые проверки — эвристические.",
                "Проверьте bootloader и Verified Boot. Для полной проверки нужен root.", true))
        }
        fileScan.suspiciousFiles.forEach { f ->
            issues.add(ScanIssue(
                when (f.severity) { 3 -> IssueSeverity.CRITICAL; 2 -> IssueSeverity.WARNING; else -> IssueSeverity.INFO },
                "Подозрительный файл: ${f.path.takeLast(60)} (ограниченный доступ)", f.reason, "Проверьте файл вручную. Обратите внимание: на Android 10+ сканирование ограничено Scoped Storage — часть файлов может быть недоступна.", true))
        }

        // Accessibility Service Abuse
        if (accessibility.suspiciousServices.isNotEmpty()) {
            val names = accessibility.suspiciousServices.joinToString(", ") { it.appName }
            issues.add(ScanIssue(IssueSeverity.CRITICAL,
                "Подозрительные Accessibility-сервисы (${accessibility.suspiciousServices.size})",
                "Активные сервисы: $names. Malware использует Accessibility для чтения экрана, перехвата ввода и обхода банковских защит.",
                "Отключите ненужные: Настройки > Специальные возможности. Проверьте каждое приложение."))
        }

        // Overlay Attack Detection
        if (overlays.hasDangerousOverlays) {
            val names = overlays.appsWithOverlay.joinToString(", ") { it.appName }
            issues.add(ScanIssue(IssueSeverity.CRITICAL,
                "Обнаружены потенциальные overlay-атаки",
                "Приложения, возможные для tapjacking: $names. Overlay-окна могут перехватывать клики.",
                "Отозвать разрешение 'Поверх других приложений' для подозрительных приложений."))
        }

        // Permission Abuse
        if (permissionAbuse.riskyApps.isNotEmpty()) {
            val critical = permissionAbuse.riskyApps.count { it.riskLevel == PermissionAbuseCheck.RiskLevel.CRITICAL }
            val high = permissionAbuse.riskyApps.count { it.riskLevel == PermissionAbuseCheck.RiskLevel.HIGH }
            issues.add(ScanIssue(
                if (critical > 0) IssueSeverity.CRITICAL else IssueSeverity.WARNING,
                "Приложения с опасными разрешениями (${permissionAbuse.riskyApps.size}, критичных: $critical, высоких: $high)",
                "Найдены приложения с рискованными комбинациями разрешений. ${permissionAbuse.riskyApps.take(3).joinToString("; ") { "${it.appName}: ${it.explanation}" }}",
                "Перейдите в Privacy Scanner для детального анализа и отзыва разрешений."))
        }

        // Hidden Apps
        if (hiddenApps.totalCount > 0) {
            val suspiciousNames = hiddenApps.hiddenApps.filter { it.riskReason.contains("Подозрительное") }
            if (suspiciousNames.isNotEmpty()) {
                issues.add(ScanIssue(IssueSeverity.CRITICAL,
                    "Скрытые приложения с подозрительными именами (${suspiciousNames.size})",
                    "Скрытые из лаунчера: ${suspiciousNames.joinToString(", ") { "${it.appName} (${it.packageName})" }}. Malware часто прячет иконки.",
                    "Удалите подозрительные приложения. Проверьте список установленных приложений в настройках."))
            } else {
                issues.add(ScanIssue(IssueSeverity.WARNING,
                    "Скрытые приложения (${hiddenApps.totalCount})",
                    "Приложения без иконки в лаунчере: ${hiddenApps.hiddenApps.take(3).joinToString(", ") { it.appName }}. Могут быть легитимными сервисами.",
                    "Проверьте каждое приложение — если не знаете, зачем оно, удалите."))
            }
        }

        // Device Admin Abuse
        if (deviceAdmins.hasSuspiciousAdmins) {
            val names = deviceAdmins.activeAdmins.joinToString(", ") { it.appName }
            issues.add(ScanIssue(IssueSeverity.CRITICAL,
                "Подозрительные Device Admin (${deviceAdmins.activeAdmins.size})",
                "Активные администраторы: $names. Device Admin защищает приложение от удаления.",
                "Отозвать права администратора: Настройки > Безопасность > Администраторы устройства."))
        }

        val appsWithRiskyPermissions = privacyApps.filter { it.hasExcessivePermissions }
        if (appsWithRiskyPermissions.isNotEmpty()) {
            issues.add(ScanIssue(IssueSeverity.WARNING, "Приложения с избыточными разрешениями (${appsWithRiskyPermissions.size})",
                "Найдены приложения, запрашивающие чувствительные разрешения без явной необходимости.",
                "Перейдите в Privacy Scanner для детального анализа и отзыва разрешений."))
        }
        val suspiciousSpywareApps = spywareApps.filter { it.indicators.isNotEmpty() }
        if (suspiciousSpywareApps.isNotEmpty()) {
            issues.add(ScanIssue(IssueSeverity.WARNING, "Подозрительные приложения по активности (${suspiciousSpywareApps.size})",
                "Приложения имеют признаки скрытого мониторинга: device admin, accessibility service, sideloaded.",
                "Перейдите в Spyware Check для детального анализа. Данная проверка — эвристика."))
        }
        val blockedRecent = firewallLogs.take(20)
        if (blockedRecent.isNotEmpty()) {
            issues.add(ScanIssue(IssueSeverity.INFO, "Недавние блокировки сети (${blockedRecent.size})",
                "В журнале Firewall обнаружены недавние попытки подключений.",
                "Проверьте правила в Firewall. Логи — эвристика, не гарантируют угрозу."))
        }
        if (firewallRules.isEmpty()) {
            issues.add(ScanIssue(IssueSeverity.INFO, "Firewall: нет активных правил (рекомендация)",
                "Для повышения защиты рекомендуется настроить правила Firewall.",
                "Перейдите в модуль Firewall для настройки правил."))
        }
        if (phishingResult.suspiciousMessages.isNotEmpty()) {
            issues.add(ScanIssue(IssueSeverity.WARNING, "Подозрительные SMS-ссылки (${phishingResult.suspiciousMessages.size})",
                "В недавних SMS найдены сообщения с ссылками и фишинговыми ключевыми словами.",
                "Проверьте отправителей вручную. Обратите внимание: доступ к SMS может быть ограничен.",
                isHeuristic = true))
        }
        if (devOptions.isUsbDebuggingEnabled) {
            issues.add(ScanIssue(IssueSeverity.WARNING, "Включена USB-отладка (эвристика)",
                "ADB-подключение — риск при физическом доступе к устройству.",
                "Отключите: Настройки > Для разработчиков > USB-отладка"))
        }
        if (devOptions.isDeveloperOptionsEnabled) {
            issues.add(ScanIssue(IssueSeverity.INFO, "Включены Developer Options (эвристика)",
                "Режим разработчика активен. Это повышает риск при физическом доступе к устройству.", "Отключите, если вы не разработчик."))
        }
        if (patch.isCritical) {
            issues.add(ScanIssue(IssueSeverity.CRITICAL, "Патч устарел (${patch.monthsOutdated} мес.)",
                "Дата патча: ${patch.patchDate}. Устройство уязвимо.",
                "Установите обновление: Настройки > О телефоне > Обновления"))
        } else if (patch.monthsOutdated >= 3) {
            issues.add(ScanIssue(IssueSeverity.WARNING, "Патч устарел (${patch.monthsOutdated} мес.)",
                "Дата патча: ${patch.patchDate}.", "Установите обновление безопасности."))
        }
        if (issues.isEmpty()) {
            issues.add(ScanIssue(IssueSeverity.INFO, "Устройство выглядит безопасным",
                "Критических проблем не обнаружено.", "Регулярно запускайте сканирование."))
        }
        val criticalCount = issues.count { it.severity == IssueSeverity.CRITICAL }
        val warningCount = issues.count { it.severity == IssueSeverity.WARNING }
        val infoCount = issues.count { it.severity == IssueSeverity.INFO }
        val riskScore = (100 - criticalCount * 30 - warningCount * 10).coerceIn(0, 100)
        return FullScanReport(riskScore, issues, criticalCount, warningCount, infoCount)
    }

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
        if (isScanning) {
            emit(FullScanState.Progress(0, 13, "Сканирование уже выполняется...", isFinished = true))
            return@flow
        }
        isScanning = true
        val totalSteps = 13
        try {
            emit(FullScanState.Progress(1, totalSteps, "Проверка root-доступа..."))
            val rootResult = withContext(Dispatchers.IO) { rootDetectionCheck.performCheck() }

            emit(FullScanState.Progress(2, totalSteps, "Проверка целостности системы..."))
            val integrityResult = withContext(Dispatchers.IO) { systemIntegrityCheck.performCheck() }

            emit(FullScanState.Progress(3, totalSteps, "Сканирование файловой системы..."))
            val fileScanResult = withContext(Dispatchers.IO) { fileSystemScanCheck.performScan() }

            emit(FullScanState.Progress(4, totalSteps, "Проверка опций разработчика..."))
            val devOptionsResult = withContext(Dispatchers.IO) { developerOptionsCheck.performCheck() }

            emit(FullScanState.Progress(5, totalSteps, "Проверка обновлений безопасности..."))
            val patchResult = withContext(Dispatchers.IO) { securityPatchCheck.performCheck() }

            emit(FullScanState.Progress(6, totalSteps, "Проверка Accessibility-сервисов..."))
            val accessibilityResult = withContext(Dispatchers.IO) { accessibilityServiceCheck.performCheck() }

            emit(FullScanState.Progress(7, totalSteps, "Проверка overlay-атак..."))
            val overlayResult = withContext(Dispatchers.IO) { overlayAttackCheck.performCheck() }

            emit(FullScanState.Progress(8, totalSteps, "Анализ злоупотребления разрешениями..."))
            val permissionAbuseResult = withContext(Dispatchers.IO) { permissionAbuseCheck.performCheck() }

            emit(FullScanState.Progress(9, totalSteps, "Поиск скрытых приложений..."))
            val hiddenAppsResult = withContext(Dispatchers.IO) { hiddenAppsCheck.performCheck() }

            emit(FullScanState.Progress(10, totalSteps, "Проверка Device Admin..."))
            val deviceAdminResult = withContext(Dispatchers.IO) { deviceAdminAbuseCheck.performCheck() }

            emit(FullScanState.Progress(11, totalSteps, "Анализ приложений и разрешений..."))
            val privacyApps = withContext(Dispatchers.IO) { privacyScannerRepository.getInstalledApps().first() }

            emit(FullScanState.Progress(12, totalSteps, "Поиск подозрительной активности..."))
            val spywareApps = withContext(Dispatchers.IO) { spywareCheckRepository.scanForSpyware().first() }

            emit(FullScanState.Progress(13, totalSteps, "Анализ сети и правил фаервола..."))
            val firewallDeferred = withContext(Dispatchers.IO) { async { firewallRepository.allRules.first() } }
            val logsDeferred = withContext(Dispatchers.IO) { async { firewallRepository.allLogs.first() } }
            val firewallRules = firewallDeferred.await()
            val firewallLogs = logsDeferred.await()

            val phishingResult = withContext(Dispatchers.IO) { phishingUrlChecker.checkRecentMessages() }

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
                phishingResult = phishingResult
            )
            val historyId = withContext(Dispatchers.IO) { saveScanHistory(report) }
            emit(FullScanState.Progress(totalSteps, totalSteps, "Сканирование завершено", isFinished = true))
            emit(FullScanState.Result(report, historyId))
        } finally {
            isScanning = false
        }
    }
}
