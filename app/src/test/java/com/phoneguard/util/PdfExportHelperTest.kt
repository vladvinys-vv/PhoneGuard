package com.phoneguard.util

import android.content.Context
import com.phoneguard.model.ScanHistory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * JVM-тесты PDF-экспорта.
 *
 * `android.graphics.pdf.PdfDocument` в обычном JVM-окружении недоступен
 * (`RuntimeException: Stub!`), поэтому здесь проверяется только отказоустойчивость:
 * экспорт обязан вернуть `null`, а не упасть. Реальное создание файла
 * проверяется инструментальным тестом `androidTest/.../PdfExportHelperTest`.
 */
class PdfExportHelperTest {

    @Test
    fun `exportScanReport returns null when external storage is unavailable`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDir(null) } returns null

        val helper = PdfExportHelper(context)
        val scan = ScanHistory(riskScore = 50, issuesFound = 1, reportJson = "{}")

        assertNull(helper.exportScanReport(scan))
    }

    @Test
    fun `exportScanReport does not throw when PDF graphics is unavailable`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tempDir = File.createTempFile("phoneguard_test", "").parentFile!!
        every { context.getExternalFilesDir(null) } returns tempDir

        val helper = PdfExportHelper(context)
        val scan = ScanHistory(riskScore = 75, issuesFound = 3, reportJson = "{}")

        // В JVM PdfDocument — заглушка, поэтому файл не создаётся, но и исключения нет.
        assertNull(helper.exportScanReport(scan))
    }
}
