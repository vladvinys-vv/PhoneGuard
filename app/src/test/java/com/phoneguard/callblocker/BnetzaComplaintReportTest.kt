package com.phoneguard.callblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BnetzaComplaintReportTest {

    @Test
    fun generatesValidReportIdFormat() {
        val id = BnetzaComplaintReport.generateReportId()
        assertNotNull(id)
        assertTrue("Report ID should start with BNETZA-", id.startsWith("BNETZA-"))
        assertTrue("Report ID should match pattern BNETZA-YYYYMMDD-XXXX", id.matches(Regex("""^BNETZA-\d{8}-\d{4}$""")))
    }

    @Test
    fun formatsStatutoryGermanSubmissionText() {
        val incident = SpamCallIncident(
            callerNumber = "+4915212345678",
            callTimestamp = "2026-11-05 10:30:00",
            violationType = BnetzaViolationType.CALLER_ID_SPOOFING,
            advertisedProductOrCompany = "Vortäuschung Bankberatung",
            wasAnswered = true,
            callDurationSeconds = 42,
            notes = "Anrufer gab sich als Sicherheitsabteilung der Bank aus"
        )

        val report = BnetzaComplaintReport(
            reportId = "BNETZA-20261105-1234",
            generatedDate = "2026-11-05",
            incidents = listOf(incident),
            reporterName = "Max Mustermann",
            reporterPhone = "+49301234567",
            reporterEmail = "max@example.de"
        )

        val text = report.toGermanSubmissionText()

        assertTrue(text.contains("Beschwerde über unerlaubte Telefonwerbung / Rufnummernmissbrauch"))
        assertTrue(text.contains("Bundesnetzagentur"))
        assertTrue(text.contains("Referat 226 - Rufnummernmissbrauch"))
        assertTrue(text.contains("rufnummernmissbrauch@bnetza.de"))
        assertTrue(text.contains("Vorgangsnummer: BNETZA-20261105-1234"))
        assertTrue(text.contains("Max Mustermann"))
        assertTrue(text.contains("+49301234567"))
        assertTrue(text.contains("max@example.de"))
        assertTrue(text.contains("+4915212345678"))
        assertTrue(text.contains("Rufnummern-Spoofing (Gefälschte Absendernummer)"))
        assertTrue(text.contains("§ 67 Abs. 1 TKG"))
        assertTrue(text.contains("Dauer: 42s"))
        assertTrue(text.contains("Vortäuschung Bankberatung"))
        assertTrue(text.contains("ausdrückliche Einwilligung"))
        assertTrue(text.contains("§ 7 Abs. 2 Nr. 2 UWG"))
    }

    @Test
    fun formatsValidBnetzaCsvExport() {
        val incident1 = SpamCallIncident(
            callerNumber = "+49891234567",
            callTimestamp = "2026-11-05 09:15:00",
            violationType = BnetzaViolationType.PING_CALL,
            wasAnswered = false
        )
        val incident2 = SpamCallIncident(
            callerNumber = "+49409876543",
            callTimestamp = "2026-11-05 11:45:00",
            violationType = BnetzaViolationType.UNERLAUBTE_WERBUNG,
            advertisedProductOrCompany = "Stromspar AG; Service",
            wasAnswered = true,
            callDurationSeconds = 120,
            notes = "Kaltakquise Stromvertrag"
        )

        val report = BnetzaComplaintReport(
            reportId = "BNETZA-20261105-9999",
            generatedDate = "2026-11-05",
            incidents = listOf(incident1, incident2)
        )

        val csv = report.toBnetzaCsv()
        val lines = csv.trim().lines()

        assertEquals(3, lines.size)
        assertEquals(
            "Vorgangsnummer;Datum_Uhrzeit;Anrufer_Rufnummer;Verstoss_Kategorie;Angenommen;Dauer_Sekunden;Beworbene_Firma;Hinweise",
            lines[0]
        )
        assertTrue(lines[1].contains("BNETZA-20261105-9999;2026-11-05 09:15:00;+49891234567;PING_CALL;false;0;;"))
        // Semicolon in company name should be replaced with comma
        assertTrue(lines[2].contains("Stromspar AG, Service"))
        assertTrue(lines[2].contains("UNERLAUBTE_WERBUNG;true;120"))
    }
}
