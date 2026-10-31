package com.phoneguard.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.phoneguard.R
import com.phoneguard.model.ScanHistory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Экспорт отчёта о сканировании в PDF.
 *
 * Исправления относительно прежней версии:
 *  - после разрыва страницы рисование продолжалось на canvas **предыдущей**
 *    страницы, поэтому всё после первого разрыва пропадало;
 *  - `getExternalFilesDir()` может вернуть `null` — это обрабатывается явно;
 *  - `PdfDocument` закрывается в `finally`, недописанный файл удаляется;
 *  - создание документа обёрнуто в `try`, чтобы на JVM (без Android graphics)
 *    экспорт просто возвращал `null`, а не падал.
 */
@Singleton
class PdfExportHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun exportScanReport(scan: ScanHistory): File? = withContext(Dispatchers.IO) {
        val externalDir = context.getExternalFilesDir(null) ?: return@withContext null
        val exportDir = File(externalDir, "exports")
        if (!exportDir.exists() && !exportDir.mkdirs()) return@withContext null

        val file = File(exportDir, "scan_report_${System.currentTimeMillis()}.pdf")
        var document: PdfDocument? = null

        try {
            val pdf = PdfDocument()
            document = pdf

            val pageWidth = 595
            val pageHeight = 842
            val margin = 60f
            val lineHeight = 28f

            var pageIndex = 1
            var yPos = margin
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null

            val titlePaint = Paint().apply {
                textSize = 22f
                isFakeBoldText = true
                color = Color.BLACK
            }
            val headerPaint = Paint().apply {
                textSize = 16f
                isFakeBoldText = true
                color = Color.DKGRAY
            }
            val bodyPaint = Paint().apply {
                textSize = 14f
                color = Color.BLACK
            }
            val dividerPaint = Paint().apply {
                strokeWidth = 1f
                color = Color.LTGRAY
            }

            fun startPage() {
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
                page = pdf.startPage(info)
                canvas = page!!.canvas
                yPos = margin
            }

            fun endPage() {
                page?.let { pdf.finishPage(it) }
                page = null
                canvas = null
            }

            /** Переключает страницу, если очередная строка не помещается. */
            fun ensureSpace(needed: Float) {
                if (yPos + needed > pageHeight - margin) {
                    endPage()
                    pageIndex++
                    startPage()
                }
            }

            /**
             * Рисует текст с переносом по ширине страницы.
             *
             * Без переноса немецкие и русские подписи (они длиннее английских) уезжали
             * за правый край листа и обрезались при печати.
             */
            fun drawParagraph(text: String, paint: Paint) {
                val maxWidth = pageWidth - 2 * margin
                var remaining = text.trim()
                while (remaining.isNotEmpty()) {
                    ensureSpace(lineHeight)
                    val fitted = paint.breakText(remaining, true, maxWidth, null)
                    if (fitted <= 0) break
                    val chunk = remaining.take(fitted).trimEnd()
                    drawLine(chunk, margin, yPos, paint)
                    yPos += lineHeight
                    remaining = remaining.drop(fitted).trimStart()
                }
            }

            fun drawLine(text: String, x: Float, y: Float, paint: Paint) {
                canvas!!.drawText(text, x, y, paint)
            }

            fun drawDivider() {
                canvas!!.drawLine(margin, yPos, pageWidth - margin, yPos, dividerPaint)
            }

            startPage()

            // Тексты берутся из ресурсов: прежний отчёт был целиком на английском
            // независимо от выбранного языка интерфейса.
            val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val reportDate = formatter.format(Date(scan.timestamp))

            drawLine(context.getString(R.string.pdf_report_title), margin, yPos, titlePaint)
            yPos += lineHeight * 1.8f
            drawDivider()
            yPos += lineHeight

            drawLine(context.getString(R.string.pdf_report_date, reportDate), margin, yPos, bodyPaint)
            yPos += lineHeight

            drawLine(context.getString(R.string.pdf_risk_score, scan.riskScore), margin, yPos, bodyPaint)
            yPos += lineHeight

            drawLine(context.getString(R.string.pdf_issues_found, scan.issuesFound), margin, yPos, bodyPaint)
            yPos += lineHeight * 1.6f

            drawDivider()
            yPos += lineHeight

            drawLine(context.getString(R.string.pdf_summary_title), margin, yPos, headerPaint)
            yPos += lineHeight * 1.4f

            drawParagraph(context.getString(R.string.pdf_summary_body), bodyPaint)

            yPos += lineHeight
            ensureSpace(lineHeight * 2)
            drawDivider()
            yPos += lineHeight

            drawLine(context.getString(R.string.pdf_details_title), margin, yPos, headerPaint)
            yPos += lineHeight * 1.4f

            val details = listOf(
                context.getString(R.string.pdf_scan_id, scan.id),
                // Раньше здесь печатались «сырые» миллисекунды эпохи.
                context.getString(R.string.pdf_scan_timestamp, reportDate),
                context.getString(R.string.pdf_risk_score, scan.riskScore),
                context.getString(R.string.pdf_issues_found, scan.issuesFound)
            )
            details.forEach { line ->
                drawParagraph(line, bodyPaint)
            }

            endPage()
            FileOutputStream(file).use { output -> pdf.writeTo(output) }

            file
        } catch (e: Exception) {
            // Недописанный PDF не оставляем в каталоге экспорта.
            runCatching { file.delete() }
            null
        } finally {
            // PdfDocument держит нативные ресурсы — закрываем всегда.
            runCatching { document?.close() }
        }
    }
}
