package com.phoneguard.callblocker

import com.phoneguard.data.local.BlockedLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Из журнала блокировок — в жалобу Bundesnetzagentur.
 *
 * Класс жалобы существовал, но не использовался нигде, а причину блокировки журнал не
 * сохранял. Здесь проверяются правила отбора: что считается нарушением, что нет, и когда
 * жалобу формировать нельзя.
 */
class BnetzaIncidentPolicyTest {

    private val day = 24L * 60 * 60 * 1000

    private fun log(
        number: String = "+49891234567",
        timestamp: Long,
        reason: String? = CallBlockDecision.BlockReason.SPAM_DATABASE.name,
        isSms: Boolean = false
    ) = BlockedLog(phoneNumber = number, isSms = isSms, timestamp = timestamp, reason = reason)

    private val format: (Long) -> String = { "ts-$it" }

    @Test
    fun `spam database and blacklist are illegal advertising`() {
        assertEquals(
            BnetzaViolationType.UNERLAUBTE_WERBUNG,
            BnetzaIncidentPolicy.violationFor(CallBlockDecision.BlockReason.SPAM_DATABASE.name)
        )
        assertEquals(
            BnetzaViolationType.UNERLAUBTE_WERBUNG,
            BnetzaIncidentPolicy.violationFor(CallBlockDecision.BlockReason.BLACKLISTED.name)
        )
    }

    @Test
    fun `hidden and unparsable numbers are caller id abuse`() {
        assertEquals(
            BnetzaViolationType.CALLER_ID_SPOOFING,
            BnetzaIncidentPolicy.violationFor(CallBlockDecision.BlockReason.HIDDEN_NUMBER.name)
        )
        assertEquals(
            BnetzaViolationType.CALLER_ID_SPOOFING,
            BnetzaIncidentPolicy.violationFor(CallBlockDecision.BlockReason.UNPARSEABLE_NUMBER.name)
        )
    }

    @Test
    fun `foreign number alone is not a violation`() {
        // § 67 TKG — про злоупотребление нумерацией, а не про зарубежные звонки.
        assertNull(
            BnetzaIncidentPolicy.violationFor(CallBlockDecision.BlockReason.INTERNATIONAL.name)
        )
    }

    @Test
    fun `unknown reason is not complaintable`() {
        // Записи до схемы 9: категорию нарушения указать нечем, придумывать её нельзя.
        assertNull(BnetzaIncidentPolicy.violationFor(null))
        assertNull(BnetzaIncidentPolicy.violationFor("SOMETHING_NEW"))
    }

    @Test
    fun `only calls inside the window are selected`() {
        val now = 1_000 * day
        val logs = listOf(
            log(timestamp = now - 5 * day),
            log(timestamp = now - 200 * day),
            log(timestamp = now + day)
        )
        val incidents = BnetzaIncidentPolicy.selectIncidents(
            logs,
            BnetzaIncidentPolicy.Window(now - 90 * day, now),
            format
        )
        assertEquals(1, incidents.size)
        assertEquals("ts-${now - 5 * day}", incidents.single().callTimestamp)
    }

    @Test
    fun `sms are excluded from a call complaint`() {
        val now = 1_000 * day
        val incidents = BnetzaIncidentPolicy.selectIncidents(
            listOf(log(timestamp = now - day, isSms = true)),
            BnetzaIncidentPolicy.Window(now - 90 * day, now),
            format
        )
        assertTrue(incidents.isEmpty())
    }

    @Test
    fun `incidents are sorted by time`() {
        val now = 1_000 * day
        val logs = listOf(
            log(number = "+49111", timestamp = now - day),
            log(number = "+49222", timestamp = now - 10 * day)
        )
        val incidents = BnetzaIncidentPolicy.selectIncidents(
            logs,
            BnetzaIncidentPolicy.Window(now - 90 * day, now),
            format
        )
        assertEquals(listOf("+49222", "+49111"), incidents.map { it.callerNumber })
    }

    @Test
    fun `no incidents means no complaint`() {
        val issues = BnetzaIncidentPolicy.validate(
            incidents = emptyList(),
            reporterName = "Max",
            reporterPhone = "+49151",
            reporterEmail = null
        )
        assertEquals(listOf(BnetzaIncidentPolicy.Issue.NO_INCIDENTS), issues)
    }

    @Test
    fun `complaint without any contact is refused`() {
        val incident = SpamCallIncident(
            callerNumber = "+49891234567",
            callTimestamp = "2026-11-05 09:15:00",
            violationType = BnetzaViolationType.UNERLAUBTE_WERBUNG
        )
        assertEquals(
            listOf(BnetzaIncidentPolicy.Issue.REPORTER_MISSING),
            BnetzaIncidentPolicy.validate(listOf(incident), null, null, null)
        )
        // Любой один контакт уже достаточен.
        assertTrue(
            BnetzaIncidentPolicy.validate(listOf(incident), null, "+49151", null).isEmpty()
        )
    }

    @Test
    fun `blank number and broken timestamp are rejected`() {
        val incident = SpamCallIncident(
            callerNumber = " ",
            callTimestamp = "05.11.2026",
            violationType = BnetzaViolationType.UNERLAUBTE_WERBUNG
        )
        val issues = BnetzaIncidentPolicy.validate(listOf(incident), "Max", null, null)
        assertTrue(BnetzaIncidentPolicy.Issue.BLANK_CALLER_NUMBER in issues)
        assertTrue(BnetzaIncidentPolicy.Issue.INVALID_TIMESTAMP in issues)
    }

    @Test
    fun `default timestamp format matches the official one`() {
        val formatted = BnetzaIncidentPolicy.formatTimestamp(0L)
        assertTrue(
            "формат должен быть YYYY-MM-DD HH:mm:ss, получено $formatted",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(formatted)
        )
    }
}
