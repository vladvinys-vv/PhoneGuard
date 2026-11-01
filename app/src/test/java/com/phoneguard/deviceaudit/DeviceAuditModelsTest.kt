package com.phoneguard.deviceaudit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оценка «чека всего телефона» — чистая логика без Android, поэтому
 * проверяется обычным JVM-тестом.
 */
class DeviceAuditModelsTest {

    private fun item(
        status: AuditStatus,
        id: String = "check",
        category: AuditCategory = AuditCategory.SECURITY
    ) = AuditItem(
        id = id,
        category = category,
        status = status,
        titleRes = 0,
        detailRes = 0
    )

    @Test
    fun `clean device scores 100`() {
        val items = listOf(
            item(AuditStatus.OK, "a"),
            item(AuditStatus.INFO, "b"),
            item(AuditStatus.OK, "c")
        )
        assertEquals(100, DeviceAuditReport.scoreOf(items))
    }

    @Test
    fun `warnings and criticals lower the score`() {
        val items = listOf(
            item(AuditStatus.WARNING, "a"),
            item(AuditStatus.CRITICAL, "b")
        )
        // 100 - 7 - 15
        assertEquals(78, DeviceAuditReport.scoreOf(items))
    }

    @Test
    fun `score never goes below zero`() {
        val items = (1..10).map { item(AuditStatus.CRITICAL, "c$it") }
        assertEquals(0, DeviceAuditReport.scoreOf(items))
    }

    @Test
    fun `report counts critical and warning items`() {
        val report = DeviceAuditReport.from(
            items = listOf(
                item(AuditStatus.CRITICAL, "a"),
                item(AuditStatus.CRITICAL, "b"),
                item(AuditStatus.WARNING, "c"),
                item(AuditStatus.OK, "d"),
                item(AuditStatus.INFO, "e")
            ),
            timestamp = 1234L
        )
        assertEquals(2, report.criticalCount)
        assertEquals(1, report.warningCount)
        assertEquals(3, report.issueCount)
        assertEquals(5, report.items.size)
        assertEquals(1234L, report.timestamp)
        assertEquals(63, report.score) // 100 - 15 - 15 - 7
    }

    @Test
    fun `report without problems has no issues`() {
        val report = DeviceAuditReport.from(listOf(item(AuditStatus.OK, "a")))
        assertEquals(0, report.issueCount)
        assertTrue(report.score >= 90)
    }

    @Test
    fun `info and ok items never lower the score`() {
        val items = (1..20).map { item(AuditStatus.INFO, "i$it") } +
            (1..20).map { item(AuditStatus.OK, "o$it") }
        assertEquals(100, DeviceAuditReport.scoreOf(items))
    }

    @Test
    fun `score is clamped to one hundred`() {
        // Отрицательных штрафов нет, но контракт «0..100» должен держаться.
        assertEquals(100, DeviceAuditReport.scoreOf(emptyList()))
        assertTrue(DeviceAuditReport.scoreOf(listOf(item(AuditStatus.OK)) ) <= 100)
    }

    @Test
    fun `single critical costs exactly fifteen points`() {
        assertEquals(85, DeviceAuditReport.scoreOf(listOf(item(AuditStatus.CRITICAL))))
    }

    @Test
    fun `single warning costs exactly seven points`() {
        assertEquals(93, DeviceAuditReport.scoreOf(listOf(item(AuditStatus.WARNING))))
    }

    @Test
    fun `report without explicit timestamp gets a current one`() {
        val before = System.currentTimeMillis()
        val report = DeviceAuditReport.from(listOf(item(AuditStatus.OK)))
        assertTrue("время отчёта должно быть текущим", report.timestamp in before..System.currentTimeMillis())
    }

    @Test
    fun `issue count sums criticals and warnings only`() {
        val report = DeviceAuditReport.from(
            listOf(
                item(AuditStatus.CRITICAL, "a"),
                item(AuditStatus.WARNING, "b"),
                item(AuditStatus.WARNING, "c"),
                item(AuditStatus.INFO, "d"),
                item(AuditStatus.OK, "e")
            )
        )
        assertEquals(3, report.issueCount)
        assertEquals(100 - 15 - 7 - 7, report.score)
    }

    @Test
    fun `empty report is perfect and issue free`() {
        val report = DeviceAuditReport.from(emptyList())
        assertEquals(100, report.score)
        assertEquals(0, report.issueCount)
        assertTrue(report.items.isEmpty())
    }
}
