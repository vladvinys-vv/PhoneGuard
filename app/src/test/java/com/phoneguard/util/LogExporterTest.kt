package com.phoneguard.util

import android.content.Context
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.ScanHistory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LogExporterTest {

    @Test
    fun `exportToJson creates valid JSON file`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tempDir = File.createTempFile("phoneguard_test", "").parentFile!!
        every { context.getExternalFilesDir(null) } returns tempDir

        val blockedLogs = listOf(BlockedLog(phoneNumber = "123", isSms = false))
        val firewallLogs = listOf(FirewallLog(packageName = "com.test", appName = "Test", timestamp = 0, connectionType = "WIFI"))
        val scanHistory = listOf(ScanHistory(timestamp = 0, riskScore = 50, issuesFound = 1, reportJson = "{}"))

        val file = LogExporter.exportToJson(context, blockedLogs, firewallLogs, scanHistory)

        assertNotNull(file)
        assertTrue(file!!.exists())
        assertTrue(file.name.startsWith("phoneguard_export_"))
        assertTrue(file.name.endsWith(".json"))

        val content = file.readText()
        assertTrue(content.contains("blockedLogs"))
        assertTrue(content.contains("firewallLogs"))
        assertTrue(content.contains("scanHistory"))
        assertTrue(content.contains("123"))
        assertTrue(content.contains("com.test"))

        file.delete()
    }

    @Test
    fun `exportToJson returns null on error`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDir(null) } returns null

        val file = LogExporter.exportToJson(context, emptyList(), emptyList(), emptyList())
        assertNull(file)
    }
}