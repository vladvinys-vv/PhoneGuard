package com.phoneguard.callblocker

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BnetzaComplaintIntentHelperTest {

    @Test
    fun createEmailIntent_formatsMailtoAndOfficialRecipient() {
        val incident = SpamCallIncident(
            callerNumber = "+491520000000",
            callTimestamp = "2026-11-06 10:15:00",
            violationType = BnetzaViolationType.CALLER_ID_SPOOFING,
            advertisedProductOrCompany = "Angeblicher Stromanbieter",
            wasAnswered = true,
            callDurationSeconds = 45,
            notes = "Anrufer forderte Zählernummer"
        )
        val report = BnetzaComplaintReport(
            reportId = "BNETZA-TEST-001",
            generatedDate = "2026-11-06",
            incidents = listOf(incident),
            reporterName = "Max Mustermann",
            reporterPhone = "+491701234567"
        )

        val intent = BnetzaComplaintIntentHelper.createEmailIntent(report)

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(Uri.parse("mailto:"), intent.data)

        val recipients = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)
        assertNotNull(recipients)
        assertEquals(1, recipients!!.size)
        assertEquals("rufnummernmissbrauch@bnetza.de", recipients[0])

        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        assertNotNull(subject)
        assertTrue(subject!!.contains("BNETZA-TEST-001"))
        assertTrue(subject.contains("+491520000000"))

        val body = intent.getStringExtra(Intent.EXTRA_TEXT)
        assertNotNull(body)
        assertTrue(body!!.contains("Bundesnetzagentur"))
        assertTrue(body.contains("§ 67 Abs. 1 TKG"))
        assertTrue(body.contains("Max Mustermann"))
    }
}
