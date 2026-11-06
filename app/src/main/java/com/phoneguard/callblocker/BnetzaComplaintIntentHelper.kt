package com.phoneguard.callblocker

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Utility for dispatching formal German BNetzA spam and spoofing complaints
 * to the Federal Network Agency (Referat 226 - Rufnummernmissbrauch).
 */
object BnetzaComplaintIntentHelper {

    /**
     * Creates an explicit mailto Intent addressed to the official BNetzA inbox
     * with statutory subject line and pre-filled German legal claim.
     */
    fun createEmailIntent(report: BnetzaComplaintReport): Intent {
        val targetCaller = report.incidents.firstOrNull()?.callerNumber ?: "Unbekannt"
        val subject = "Beschwerde Rufnummernmissbrauch: ${report.reportId} ($targetCaller)"
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(BnetzaComplaintReport.OFFICIAL_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, report.toGermanSubmissionText())
        }
    }

    /**
     * Creates an Android share chooser Intent for saving or sending the complaint document.
     */
    fun createShareIntent(context: Context, report: BnetzaComplaintReport, chooserTitle: String = "BNetzA Beschwerde einreichen"): Intent {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BNetzA Beschwerde: ${report.reportId}")
            putExtra(Intent.EXTRA_TEXT, report.toGermanSubmissionText())
        }
        return Intent.createChooser(shareIntent, chooserTitle)
    }
}
