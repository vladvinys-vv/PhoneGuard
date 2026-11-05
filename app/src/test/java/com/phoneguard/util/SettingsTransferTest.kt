package com.phoneguard.util

import com.phoneguard.util.SettingsTransfer.SettingEntry
import com.phoneguard.util.SettingsTransfer.RejectionReason
import com.phoneguard.util.SettingsTransfer.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перенос настроек на новый телефон.
 *
 * Резервная копия выключена осознанно (`dataExtractionRules` исключает и облако, и D2D),
 * поэтому единственный путь — файл. Ошибка здесь стоит дорого: слишком широкий список
 * переносимого вынесет из песочницы хэш PIN, слишком узкий — оставит пользователя без
 * настроек защиты на новом телефоне.
 */
class SettingsTransferTest {

    private fun booleanEntry(key: String, value: Boolean) =
        SettingEntry(key, Type.BOOLEAN, value.toString())

    @Test
    fun `round trip keeps entries and their order`() {
        val entries = listOf(
            booleanEntry("block_unknown", true),
            SettingEntry("scan_interval_minutes", Type.INT, "3"),
            SettingEntry("trusted_contact_since", Type.LONG, "1700000000000"),
            SettingEntry("backup_number", Type.STRING, "+4915112345678")
        )
        val decoded = SettingsTransfer.decode(SettingsTransfer.encode(entries)).getOrThrow()
        assertEquals(1, decoded.version)
        assertEquals(entries, decoded.entries)
        assertEquals(0, decoded.skippedLines)
    }

    @Test
    fun `format header is present and readable`() {
        val text = SettingsTransfer.encode(listOf(booleanEntry("block_unknown", true)))
        assertTrue(text.contains("format=${SettingsTransfer.FORMAT_ID}"))
        assertTrue(text.contains("version=${SettingsTransfer.VERSION}"))
        assertTrue(text.contains("boolean\tblock_unknown\ttrue"))
    }

    @Test
    fun `secrets are never written to the file`() {
        val entries = listOf(
            SettingEntry("pin_code_hash", Type.STRING, "deadbeef"),
            SettingEntry("last_known_imsi", Type.STRING, "262011234567890"),
            SettingEntry("lost_mode_active", Type.BOOLEAN, "true"),
            booleanEntry("block_unknown", true)
        )
        val text = SettingsTransfer.encode(entries)
        assertFalse(text.contains("pin_code_hash"))
        assertFalse(text.contains("deadbeef"))
        assertFalse(text.contains("last_known_imsi"))
        assertFalse(text.contains("lost_mode_active"))
        assertTrue(text.contains("block_unknown"))
    }

    @Test
    fun `every never transferred key is refused by the policy`() {
        SettingsTransfer.NEVER_TRANSFERRED.forEach { key ->
            assertFalse("$key должен быть запрещён", SettingsTransfer.isTransferable(key))
        }
        assertTrue(SettingsTransfer.isTransferable("block_unknown"))
        assertFalse(SettingsTransfer.isTransferable(""))
    }

    @Test
    fun `hand edited file cannot sneak a secret in`() {
        val text = SettingsTransfer.encode(emptyList()) + "string\tpin_code_hash\tabc\n"
        val bundle = SettingsTransfer.decode(text).getOrThrow()
        val plan = SettingsTransfer.planImport(bundle)
        assertTrue(plan.accepted.isEmpty())
        assertEquals(
            listOf(RejectionReason.NOT_TRANSFERABLE),
            plan.rejected.map { it.reason }
        )
    }

    @Test
    fun `invalid value for a boolean is rejected`() {
        val text = SettingsTransfer.encode(emptyList()) + "boolean\tblock_unknown\tmaybe\n"
        val plan = SettingsTransfer.planImport(SettingsTransfer.decode(text).getOrThrow())
        assertTrue(plan.accepted.isEmpty())
        assertEquals(RejectionReason.INVALID_VALUE, plan.rejected.single().reason)
    }

    @Test
    fun `invalid value for a number is rejected`() {
        val text = SettingsTransfer.encode(emptyList()) + "int\tscan_interval_minutes\tмного\n"
        val plan = SettingsTransfer.planImport(SettingsTransfer.decode(text).getOrThrow())
        assertEquals(RejectionReason.INVALID_VALUE, plan.rejected.single().reason)
    }

    @Test
    fun `last duplicate wins and appears once`() {
        val text = SettingsTransfer.encode(
            listOf(booleanEntry("block_unknown", true), booleanEntry("block_unknown", false))
        )
        val plan = SettingsTransfer.planImport(SettingsTransfer.decode(text).getOrThrow())
        assertEquals(1, plan.accepted.size)
        assertEquals("false", plan.accepted.single().value)
    }

    @Test
    fun `tabs and newlines in strings survive escaping`() {
        val value = "первая\tвторая\nтретья\\конец"
        val encoded = SettingsTransfer.encode(listOf(SettingEntry("trusted_message", Type.STRING, value)))
        // Перевод строки не должен разрывать запись на две.
        val decoded = SettingsTransfer.decode(encoded).getOrThrow()
        assertEquals(value, decoded.entries.single().value)
        assertEquals(0, decoded.skippedLines)
    }

    @Test
    fun `broken lines are skipped but do not abort the import`() {
        val text = SettingsTransfer.encode(listOf(booleanEntry("block_unknown", true))) +
            "мусор без разделителей\n" +
            "number\tunknown_type\t1\n"
        val decoded = SettingsTransfer.decode(text).getOrThrow()
        assertEquals(1, decoded.entries.size)
        assertEquals(2, decoded.skippedLines)
        assertEquals(1, SettingsTransfer.planImport(decoded).accepted.size)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val text = SettingsTransfer.encode(listOf(booleanEntry("block_unknown", true))) +
            "\n# комментарий\n\n"
        val decoded = SettingsTransfer.decode(text).getOrThrow()
        assertEquals(1, decoded.entries.size)
        assertEquals(0, decoded.skippedLines)
    }

    @Test
    fun `foreign file is refused`() {
        val failure = SettingsTransfer.decode("{\"blockedLogs\":[]}").exceptionOrNull()
        assertTrue("должна быть ошибка формата", failure != null)
    }

    @Test
    fun `file from a newer app version is refused`() {
        val text = "format=${SettingsTransfer.FORMAT_ID}\nversion=${SettingsTransfer.VERSION + 1}\n"
        val failure = SettingsTransfer.decode(text).exceptionOrNull()
        assertTrue("новая версия должна быть отклонена", failure != null)
        assertTrue(failure!!.message!!.contains("новее"))
    }

    @Test
    fun `file without version is refused`() {
        val text = "format=${SettingsTransfer.FORMAT_ID}\nboolean\tblock_unknown\ttrue\n"
        assertTrue(SettingsTransfer.decode(text).isFailure)
    }

    @Test
    fun `empty file yields an empty plan`() {
        val decoded = SettingsTransfer.decode(SettingsTransfer.encode(emptyList())).getOrThrow()
        assertTrue(decoded.entries.isEmpty())
        assertTrue(SettingsTransfer.planImport(decoded).nothingToImport)
    }
}
