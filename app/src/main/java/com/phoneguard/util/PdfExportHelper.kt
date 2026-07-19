package com.phoneguard.util

import android.content.Context
import android.graphics.pdf.PdfDocument
import com.phoneguard.model.FullScanReport
import com.phoneguard.model.ScanHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfExportHelper @Inject constructor(
    private val context: Context
) {
    suspend fun exportScanReport(scan: ScanHistory): File? = withContext(Dispatchers.IO) {
        return@withContext try {
            val exportDir = File(context.getExternalFilesDir(null), "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val file = File(exportDir, "scan_report_$timestamp.pdf")

            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val title = "PhoneGuard Scan Report"
            val date = "Date: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(scan.timestamp))}"
            val risk = "Risk Score: ${scan.riskScore}/100"
            val issues = "Issues Found: ${scan.issuesFound}"

            canvas.drawText(title, 50f, 50f, android.graphics.Paint().apply {
                textSize = 24f
                isFakeBoldText = true
            })
            canvas.drawText(date, 50f, 100f, android.graphics.Paint().apply { textSize = 16f })
            canvas.drawText(risk, 50f, 140f, android.graphics.Paint().apply { textSize = 16f })
            canvas.drawText(issues, 50f, 180f, android.graphics.Paint().apply { textSize = 16f })

            document.finishPage(page)
            document.writeTo(FileOutputStream(file))
            document.close()

            file
        } catch (e: Exception) {
            null
        }
    }
}