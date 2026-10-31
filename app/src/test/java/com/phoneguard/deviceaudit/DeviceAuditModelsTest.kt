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
        assertEquals(51, report.score) // 100 - 15 - 15 - 7
    }

    @Test
    fun `report without problems has no issues`() {
        val report = DeviceAuditReport.from(listOf(item(AuditStatus.OK, "a")))
        assertEquals(0, report.issueCount)
        assertTrue(report.score >= 90)
    }
}
