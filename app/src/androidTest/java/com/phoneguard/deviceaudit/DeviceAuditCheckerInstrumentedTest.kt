package com.phoneguard.deviceaudit

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет, что аудит устройства реально работает на Android: возвращает все
 * проверки, не падает на недоступных настройках и укладывается в диапазон оценки.
 *
 * Это единственный способ поймать ошибки API-уровней (Settings.Global, StatFs,
 * NotificationManagerCompat и т.п.) — на JVM они не воспроизводятся.
 */
class DeviceAuditCheckerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun audit_returnsAllChecksWithUniqueIds() = runBlocking {
        val report = DeviceAuditChecker(context).performAudit()

        assertEquals(20, report.items.size)
        assertEquals(report.items.size, report.items.map { it.id }.toSet().size)
        assertTrue(report.score in 0..100)
        assertTrue(report.criticalCount >= 0)
        assertTrue(report.warningCount >= 0)
        assertEquals(
            report.items.count { it.status == AuditStatus.CRITICAL },
            report.criticalCount
        )
    }

    @Test
    fun audit_titlesAndDetailsResolveToText() = runBlocking {
        val checker = DeviceAuditChecker(context)
        val report = checker.performAudit()

        report.items.forEach { item ->
            assertTrue("пустой заголовок у ${item.id}", checker.getString(item.titleRes).isNotBlank())
            val detail = if (item.detailArgs.isEmpty()) {
                checker.getString(item.detailRes)
            } else {
                checker.getString(item.detailRes, *item.detailArgs.toTypedArray())
            }
            assertTrue("пустая деталь у ${item.id}", detail.isNotBlank())
            // В строке не должно остаться неподставленных плейсхолдеров вроде %1$s
            assertTrue("неподставленный плейсхолдер у ${item.id}: $detail", '%' !in detail)
        }
    }

    @Test
    fun audit_alwaysReportsOsVersionAndModel() = runBlocking {
        val report = DeviceAuditChecker(context).performAudit()
        val ids = report.items.map { it.id }
        assertTrue("os_version" in ids)
        assertTrue("device_model" in ids)
        assertTrue("screen_lock" in ids)
        assertTrue("free_space" in ids)
    }
}
