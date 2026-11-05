package com.phoneguard.callblocker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Types of statutory violations reported to the German Federal Network Agency (Bundesnetzagentur - BNetzA)
 * pursuant to § 67 TKG (Telekommunikationsgesetz) and § 7 UWG (Gesetz gegen den unlauteren Wettbewerb).
 */
enum class BnetzaViolationType(val germanLabel: String, val legalSection: String) {
    UNERLAUBTE_WERBUNG("Unerlaubte Telefonwerbung (Cold Calling)", "§ 7 Abs. 2 Nr. 2 UWG"),
    CALLER_ID_SPOOFING("Rufnummern-Spoofing (Gefälschte Absendernummer)", "§ 67 Abs. 1 TKG"),
    PING_CALL("Ping-Anruf (Lockanruf zu teuren Sonderrufnummern)", "§ 67 Abs. 1 TKG"),
    AUTOMATED_VOICE_BOT("Automatisierte Bandansage / Robocall", "§ 7 Abs. 2 Nr. 1 UWG"),
    GEWINNSPIEL_LOTTERIE("Gewinnspiel- / Lotterie-Betrug", "§ 7 UWG, § 263 StGB")
}

/**
 * Detailed log record of a spam or spoofed call incident for BNetzA evidence submission.
 */
data class SpamCallIncident(
    val callerNumber: String,
    val callTimestamp: String, // "YYYY-MM-DD HH:mm:ss"
    val violationType: BnetzaViolationType,
    val advertisedProductOrCompany: String? = null,
    val wasAnswered: Boolean = false,
    val callDurationSeconds: Int = 0,
    val notes: String? = null
)

/**
 * Formal complaint document formatted for direct submission to Bundesnetzagentur Referat 226
 * (rufnummernmissbrauch@bnetza.de) or via the BNetzA online complaint portal.
 */
data class BnetzaComplaintReport(
    val reportId: String,
    val generatedDate: String,
    val incidents: List<SpamCallIncident>,
    val reporterName: String? = null,
    val reporterPhone: String? = null,
    val reporterEmail: String? = null
) {
    companion object {
        const val OFFICIAL_AGENCY = "Bundesnetzagentur für Elektrizität, Gas, Telekommunikation, Post und Eisenbahnen"
        const val OFFICIAL_REFERAT = "Referat 226 - Rufnummernmissbrauch"
        const val OFFICIAL_EMAIL = "rufnummernmissbrauch@bnetza.de"
        const val OFFICIAL_ADDRESS = "Postfach 80 01, 53105 Bonn"

        fun generateReportId(): String {
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(Date())
            val rand = (1000..9999).random()
            return "BNETZA-$dateStr-$rand"
        }
    }

    /**
     * Generates statutory German formal complaint text matching BNetzA requirements.
     */
    fun toGermanSubmissionText(): String {
        val sb = StringBuilder()
        sb.appendLine("Beschwerde über unerlaubte Telefonwerbung / Rufnummernmissbrauch")
        sb.appendLine("An: $OFFICIAL_AGENCY")
        sb.appendLine("$OFFICIAL_REFERAT, $OFFICIAL_ADDRESS")
        sb.appendLine("E-Mail: $OFFICIAL_EMAIL")
        sb.appendLine()
        sb.appendLine("Vorgangsnummer: $reportId")
        sb.appendLine("Datum der Erstellung: $generatedDate")
        sb.appendLine()

        if (!reporterName.isNullOrBlank()) {
            sb.appendLine("Angaben zum Beschwerdeführer (Angerufener):")
            sb.appendLine("  Name: $reporterName")
            if (!reporterPhone.isNullOrBlank()) sb.appendLine("  Betroffene Rufnummer: $reporterPhone")
            if (!reporterEmail.isNullOrBlank()) sb.appendLine("  E-Mail: $reporterEmail")
            sb.appendLine()
        }

        sb.appendLine("Hiermit lege ich formell Beschwerde wegen Verstoßes gegen § 67 TKG bzw. § 7 UWG ein.")
        sb.appendLine("Dokumentierte Vorfälle (insgesamt ${incidents.size}):")
        sb.appendLine("--------------------------------------------------------------------------------")

        incidents.forEachIndexed { idx, inc ->
            sb.appendLine("Vorfall #${idx + 1}:")
            sb.appendLine("  Anrufende Rufnummer: ${inc.callerNumber}")
            sb.appendLine("  Zeitpunkt des Anrufs: ${inc.callTimestamp}")
            sb.appendLine("  Verstoßkategorie: ${inc.violationType.germanLabel} (${inc.violationType.legalSection})")
            sb.appendLine("  Anruf entgegengenommen: ${if (inc.wasAnswered) "Ja (Dauer: ${inc.callDurationSeconds}s)" else "Nein (Klingelzeichen/Ping)"}")
            if (!inc.advertisedProductOrCompany.isNullOrBlank()) {
                sb.appendLine("  Beworbenes Produkt / Firma: ${inc.advertisedProductOrCompany}")
            }
            if (!inc.notes.isNullOrBlank()) {
                sb.appendLine("  Sachverhaltsbeschreibung: ${inc.notes}")
            }
            sb.appendLine("--------------------------------------------------------------------------------")
        }

        sb.appendLine()
        sb.appendLine("Ich versichere, dass ich zu keinem Zeitpunkt eine vorherige ausdrückliche Einwilligung")
        sb.appendLine("in werbliche Telefonanrufe gemäß § 7 Abs. 2 Nr. 2 UWG gegenüber den genannten Anrufern erteilt habe.")
        sb.appendLine("Ich bitte um Einleitung von Ermittlungen sowie Prüfung von Maßnahmen nach § 67 TKG")
        sb.appendLine("(z. B. Rufnummernabschaltung, Rechnungslegungs- und Inkassierungsverbote).")
        sb.appendLine()
        sb.appendLine("Erstellt mit PhoneGuard (Automatisierter Schutz vor unerwünschten Anrufen)")

        return sb.toString()
    }

    /**
     * Exports incident logs as official BNetzA CSV format for bulk evidence submission.
     */
    fun toBnetzaCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("Vorgangsnummer;Datum_Uhrzeit;Anrufer_Rufnummer;Verstoss_Kategorie;Angenommen;Dauer_Sekunden;Beworbene_Firma;Hinweise")
        for (inc in incidents) {
            val safeCompany = (inc.advertisedProductOrCompany ?: "").replace(";", ",")
            val safeNotes = (inc.notes ?: "").replace(";", ",").replace("\n", " ")
            sb.appendLine(
                "$reportId;${inc.callTimestamp};${inc.callerNumber};${inc.violationType.name};${inc.wasAnswered};${inc.callDurationSeconds};$safeCompany;$safeNotes"
            )
        }
        return sb.toString()
    }
}
