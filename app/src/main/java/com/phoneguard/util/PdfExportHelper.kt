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
            val pageWidth = 595
            val pageHeight = 842
            val margin = 60f
            val lineHeight = 28f

            var yPos = 60f
            var currentPageIndex = 1
            var page: PdfDocument.Page? = null

            fun ensureSpace(needed: Float) {
                if (yPos + needed > pageHeight - margin) {
                    document.finishPage(page!!)
                    currentPageIndex++
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageIndex).create()
                    page = document.startPage(pageInfo)
                    yPos = margin
                }
            }

            val titlePaint = android.graphics.Paint().apply {
                textSize = 22f
                isFakeBoldText = true
                color = android.graphics.Color.BLACK
            }
            val headerPaint = android.graphics.Paint().apply {
                textSize = 16f
                isFakeBoldText = true
                color = android.graphics.Color.DKGRAY
            }
            val bodyPaint = android.graphics.Paint().apply {
                textSize = 14f
                color = android.graphics.Color.BLACK
            }
            val dividerPaint = android.graphics.Paint().apply {
                strokeWidth = 1f
                color = android.graphics.Color.LTGRAY
            }

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageIndex).create()
            page = document.startPage(pageInfo)
            val canvas = page!!.canvas

            canvas.drawText("PhoneGuard Scan Report", margin, yPos, titlePaint)
            yPos += lineHeight * 1.8f

            canvas.drawLine(margin, yPos, pageWidth - margin, yPos, dividerPaint)
            yPos += lineHeight

            val dateText = "Date: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(scan.timestamp))}"
            canvas.drawText(dateText, margin, yPos, bodyPaint)
            yPos += lineHeight

            val riskText = "Risk Score: ${scan.riskScore}/100"
            canvas.drawText(riskText, margin, yPos, bodyPaint)
            yPos += lineHeight

            val issuesText = "Issues Found: ${scan.issuesFound}"
            canvas.drawText(issuesText, margin, yPos, bodyPaint)
            yPos += lineHeight * 1.6f

            canvas.drawLine(margin, yPos, pageWidth - margin, yPos, dividerPaint)
            yPos += lineHeight

            canvas.drawText("Summary", margin, yPos, headerPaint)
            yPos += lineHeight * 1.4f

            val summaryLines = listOf(
                "This report was generated automatically by PhoneGuard.",
                "Review the issues below and follow the recommendations to improve your device security.",
                "For more details, open the full scan history in the app."
            )
            summaryLines.forEach { line ->
                ensureSpace(lineHeight)
                canvas.drawText(line, margin, yPos, bodyPaint)
                yPos += lineHeight
            }

            yPos += lineHeight
            ensureSpace(lineHeight * 2)
            canvas.drawLine(margin, yPos, pageWidth - margin, yPos, dividerPaint)
            yPos += lineHeight

            canvas.drawText("Report Details", margin, yPos, headerPaint)
            yPos += lineHeight * 1.4f

            val details = listOf(
                "Scan ID: ${scan.id}",
                "Timestamp: ${scan.timestamp}",
                "Risk Score: ${scan.riskScore}",
                "Issues Found: ${scan.issuesFound}"
            )
            details.forEach { line ->
                ensureSpace(lineHeight)
                canvas.drawText(line, margin, yPos, bodyPaint)
                yPos += lineHeight
            }

            document.finishPage(page!!)
            document.writeTo(FileOutputStream(file))
            document.close()

            file
        } catch (e: Exception) {
            null
        }
    }
}
