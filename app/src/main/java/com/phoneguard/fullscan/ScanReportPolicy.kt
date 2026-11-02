package com.phoneguard.fullscan

import com.phoneguard.model.FullScanReport
import com.phoneguard.model.IssueSeverity
import com.phoneguard.model.ScanIssue

/**
 * Подсчёт находок и оценка риска для отчёта полного сканирования.
 *
 * Раньше это было четыре строки в `FullScanOrchestrator.buildReport()`, и в них
 * пряталась ошибка: заглушка «проблем не найдено» (INFO-запись, которую получает
 * чистый телефон) добавлялась в список **до** подсчёта, поэтому `infoCount` был
 * равен единице. Следствия: история сканирований показывала «найдено проблем: 1»
 * на полностью чистом устройстве, а `AnalyticsHelper.logScanCompleted()` получал
 * ту же единицу.
 *
 * Здесь правила отделены от Android и проверяются JVM-тестами:
 *  - заглушки ([ScanIssue.isPlaceholder]) в счётчики не попадают;
 *  - `riskScore` штрафуется за критичные (−30) и предупреждения (−10) и
 *    ограничивается диапазоном 0..100;
 *  - информационные находки на оценку не влияют.
 */
object ScanReportPolicy {

    /** Штраф за одну критичную находку. */
    const val CRITICAL_PENALTY = 30

    /** Штраф за одно предупреждение. */
    const val WARNING_PENALTY = 10

    data class Counts(
        val critical: Int = 0,
        val warning: Int = 0,
        val info: Int = 0
    ) {
        /** Настоящих находок: заглушки уже исключены. */
        val total: Int get() = critical + warning + info
    }

    /** Считает находки, игнорируя заглушку «проблем не найдено». */
    fun counts(issues: List<ScanIssue>): Counts {
        var critical = 0
        var warning = 0
        var info = 0
        for (issue in issues) {
            if (issue.isPlaceholder) continue
            when (issue.severity) {
                IssueSeverity.CRITICAL -> critical++
                IssueSeverity.WARNING -> warning++
                IssueSeverity.INFO -> info++
            }
        }
        return Counts(critical, warning, info)
    }

    /** Оценка 0..100, где 100 — проблем не найдено. */
    fun riskScore(counts: Counts): Int =
        (100 - counts.critical * CRITICAL_PENALTY - counts.warning * WARNING_PENALTY)
            .coerceIn(0, 100)

    /** Готовый отчёт: счётчики и оценка всегда согласованы со списком находок. */
    fun build(issues: List<ScanIssue>): FullScanReport {
        val counts = counts(issues)
        return FullScanReport(
            riskScore = riskScore(counts),
            issues = issues,
            criticalCount = counts.critical,
            warningCount = counts.warning,
            infoCount = counts.info
        )
    }

    /**
     * Суммарное число настоящих находок в уже готовом отчёте.
     *
     * Для экранов и аналитики: `report.issues.size` включает заглушку, поэтому
     * чистый телефон показывал «1 проблема».
     */
    fun findingCount(report: FullScanReport): Int =
        report.criticalCount + report.warningCount + report.infoCount
}
