package com.phoneguard.fullscan

import com.phoneguard.model.FullScanReport
import com.phoneguard.model.IssueSeverity
import com.phoneguard.model.ScanIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Счётчики и оценка риска отчёта.
 *
 * Главный тест — «чистый телефон»: в отчёте есть заглушка «проблем не найдено»,
 * но находок ноль. Раньше эта заглушка попадала в `infoCount`, и история
 * сканирований показывала «найдено проблем: 1».
 */
class ScanReportPolicyTest {

    private fun issue(
        severity: IssueSeverity,
        placeholder: Boolean = false
    ) = ScanIssue(
        severity = severity,
        title = "t",
        description = "d",
        recommendation = "r",
        isPlaceholder = placeholder
    )

    @Test
    fun `чистый телефон без заглушки — ноль находок и 100`() {
        val counts = ScanReportPolicy.counts(emptyList())
        assertEquals(0, counts.total)
        assertEquals(100, ScanReportPolicy.riskScore(counts))
    }

    @Test
    fun `заглушка «проблем не найдено» не считается находкой`() {
        val issues = listOf(issue(IssueSeverity.INFO, placeholder = true))
        val counts = ScanReportPolicy.counts(issues)
        assertEquals(0, counts.critical)
        assertEquals(0, counts.warning)
        assertEquals(0, counts.info)
        assertEquals(0, counts.total)
        assertEquals(100, ScanReportPolicy.riskScore(counts))
    }

    @Test
    fun `отчёт чистого телефона содержит заглушку, но ноль в счётчиках`() {
        val issues = listOf(issue(IssueSeverity.INFO, placeholder = true))
        val report = ScanReportPolicy.build(issues)
        assertEquals(1, report.issues.size)
        assertEquals(0, report.infoCount)
        assertEquals(0, ScanReportPolicy.findingCount(report))
        assertEquals(100, report.riskScore)
    }

    @Test
    fun `заглушка с чужим уровнем всё равно игнорируется`() {
        val counts = ScanReportPolicy.counts(listOf(issue(IssueSeverity.CRITICAL, placeholder = true)))
        assertEquals(0, counts.total)
    }

    @Test
    fun `критичная находка штрафует на 30, предупреждение на 10`() {
        assertEquals(70, ScanReportPolicy.riskScore(ScanReportPolicy.Counts(critical = 1)))
        assertEquals(90, ScanReportPolicy.riskScore(ScanReportPolicy.Counts(warning = 1)))
        assertEquals(40, ScanReportPolicy.riskScore(ScanReportPolicy.Counts(critical = 1, warning = 3)))
    }

    @Test
    fun `информационные находки на оценку не влияют`() {
        assertEquals(100, ScanReportPolicy.riskScore(ScanReportPolicy.Counts(info = 12)))
    }

    @Test
    fun `оценка не уходит в минус`() {
        assertEquals(0, ScanReportPolicy.riskScore(ScanReportPolicy.Counts(critical = 4)))
        assertEquals(0, ScanReportPolicy.riskScore(ScanReportPolicy.Counts(critical = 10, warning = 10)))
    }

    @Test
    fun `счётчики согласованы со списком находок`() {
        val issues = listOf(
            issue(IssueSeverity.CRITICAL),
            issue(IssueSeverity.WARNING),
            issue(IssueSeverity.WARNING),
            issue(IssueSeverity.INFO),
            issue(IssueSeverity.INFO, placeholder = true)
        )
        val report = ScanReportPolicy.build(issues)
        assertEquals(1, report.criticalCount)
        assertEquals(2, report.warningCount)
        assertEquals(1, report.infoCount)
        assertEquals(4, ScanReportPolicy.findingCount(report))
        // Один пункт штрафа крит. (30) + два предупреждения (20).
        assertEquals(50, report.riskScore)
        assertSame(issues, report.issues)
    }

    @Test
    fun `findingCount не зависит от заглушки`() {
        val withPlaceholder = ScanReportPolicy.build(listOf(issue(IssueSeverity.INFO, placeholder = true)))
        val without = FullScanReport(riskScore = 100, issues = emptyList())
        assertEquals(ScanReportPolicy.findingCount(without), ScanReportPolicy.findingCount(withPlaceholder))
        assertTrue(withPlaceholder.issues.isNotEmpty())
    }
}
