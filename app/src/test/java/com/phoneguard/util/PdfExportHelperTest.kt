package com.phoneguard.util

import android.content.Context
import com.phoneguard.model.ScanHistory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfExportHelperTest {

    @Test
    fun `exportScanReport creates PDF file`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tempDir = File.createTempFile("phoneguard_test", "").parentFile!!
        every { context.getExternalFilesDir(null) } returns tempDir

        val helper = PdfExportHelper(context)
        val scan = ScanHistory(timestamp = System.currentTimeMillis(), riskScore = 75, issuesFound = 3, reportJson = "{}")
        val file = helper.exportScanReport(scan)

        assertNotNull(file)
        assertTrue(file!!.exists())
        assertTrue(file.name.startsWith("scan_report_"))
        assertTrue(file.name.endsWith(".pdf"))

        file.delete()
    }

    @Test
    fun `exportScanReport returns null on error`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDir(null) } returns null

        val helper = PdfExportHelper(context)
        val scan = ScanHistory(timestamp = System.currentTimeMillis(), riskScore = 50, issuesFound = 1, reportJson = "{}")
        val file = helper.exportScanReport(scan)

        assertNull(file)
    }
}
