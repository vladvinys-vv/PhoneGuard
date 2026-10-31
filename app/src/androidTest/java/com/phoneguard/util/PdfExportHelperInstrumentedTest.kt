package com.phoneguard.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.ScanHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментальный тест: на устройстве `PdfDocument` доступен, поэтому файл
 * действительно создаётся. На JVM этот сценарий проверить нельзя.
 */
@RunWith(AndroidJUnit4::class)
class PdfExportHelperInstrumentedTest {

    @Test
    fun exportScanReport_createsPdfFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = PdfExportHelper(context)
        val scan = ScanHistory(
            timestamp = System.currentTimeMillis(),
            riskScore = 75,
            issuesFound = 3,
            reportJson = "{}"
        )

        val file = helper.exportScanReport(scan)

        assertNotNull(file)
        assertTrue(file!!.exists())
        assertTrue(file.name.startsWith("scan_report_"))
        assertTrue(file.name.endsWith(".pdf"))
        assertTrue(file.length() > 0)

        file.delete()
    }
}
