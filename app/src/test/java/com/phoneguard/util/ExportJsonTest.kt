package com.phoneguard.util

import com.phoneguard.data.local.BlockedLog
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.ScanHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сериализация выгрузки журналов.
 *
 * Раньше JSON собирался через `org.json`, и `connectionType = WIFI` попадало в файл
 * объектом enum'а — как минимум не тем, что ожидает потребитель выгрузки. Здесь
 * формат закреплён текстово, а экранирование проверяется посимвольно.
 */
class ExportJsonTest {

    private fun blocked(number: String) = BlockedLog(phoneNumber = number, isSms = false)

    private fun firewall(ip: String? = "1.2.3.4", domain: String? = null) = FirewallLog(
        packageName = "com.example.app",
        appName = "Example",
        ipAddress = ip,
        domainName = domain,
        timestamp = 1_700_000_000_000L,
        connectionType = FirewallLog.ConnectionType.WIFI,
        trafficDirection = FirewallLog.TrafficDirection.OUTBOUND
    )

    private fun scanEntry() = ScanHistory(
        timestamp = 42L,
        riskScore = 70,
        issuesFound = 3,
        reportJson = "{\"a\":1}"
    )

    private fun document(
        blockedLogs: List<BlockedLog> = emptyList(),
        firewallLogs: List<FirewallLog> = emptyList(),
        scanHistory: List<ScanHistory> = emptyList()
    ): String = StringBuilder().also { ExportJson.write(it, blockedLogs, firewallLogs, scanHistory) }.toString()

    @Test
    fun emptyExportStillHasAllSections() {
        val json = document()
        assertTrue(json.contains("\"blockedLogs\": []"))
        assertTrue(json.contains("\"firewallLogs\": []"))
        assertTrue(json.contains("\"scanHistory\": []"))
        assertTrue(json.trimEnd().endsWith("}"))
    }

    @Test
    fun enumsAreWrittenAsQuotedNames() {
        val json = document(firewallLogs = listOf(firewall()))
        assertTrue("тип соединения должен быть строкой: $json", json.contains("\"connectionType\": \"WIFI\""))
        assertTrue(json.contains("\"trafficDirection\": \"OUTBOUND\""))
    }

    @Test
    fun nullFieldsAreWrittenAsJsonNull() {
        val json = document(firewallLogs = listOf(firewall(ip = null, domain = null)))
        assertTrue(json.contains("\"ipAddress\": null"))
        assertTrue(json.contains("\"domainName\": null"))
    }

    @Test
    fun stringsWithQuotesAndNewlinesAreEscaped() {
        val json = document(blockedLogs = listOf(blocked("+49\"170\n1234\\")))
        assertTrue(json.contains("+49\\\"170\\n1234\\\\"))
        // Сырой перевод строки внутри значения означает, что экранирования нет.
        assertFalse(json.contains("+49\"170\n1234"))
    }

    @Test
    fun escapingCoversControlCharacters() {
        assertEquals("\"a\\u0001b\"", ExportJson.quote("a\u0001b"))
        assertEquals("\"tab\\there\"", ExportJson.quote("tab\there"))
        assertEquals("\"cr\\r\"", ExportJson.quote("cr\r"))
        assertEquals("\"bell\\u0007\"", ExportJson.quote("bell\u0007"))
    }

    @Test
    fun nonAsciiTextIsKeptAsIs() {
        assertEquals("\"Müller, Ёж\"", ExportJson.quote("Müller, Ёж"))
    }

    @Test
    fun bracesAreBalancedForMultipleItems() {
        val json = document(
            blockedLogs = listOf(blocked("111"), blocked("222")),
            firewallLogs = listOf(firewall(), firewall()),
            scanHistory = listOf(scanEntry(), scanEntry(), scanEntry())
        )
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        // 1 корневой объект + 2 + 2 + 3 записи (плюс фигурные скобки внутри строки reportJson).
        assertTrue("ожидалось не меньше восьми объектов", json.count { it == '{' } >= 8)
    }

    @Test
    fun reportJsonIsEscapedNotInlined() {
        val json = document(scanHistory = listOf(scanEntry()))
        assertTrue("вложенный JSON должен быть строкой: $json", json.contains("\"reportJson\": \"{\\\"a\\\":1}\""))
    }

    @Test
    fun recordValuesArePresent() {
        val json = document(
            blockedLogs = listOf(blocked("+491701234567")),
            firewallLogs = listOf(firewall()),
            scanHistory = listOf(scanEntry())
        )
        assertTrue(json.contains("+491701234567"))
        assertTrue(json.contains("com.example.app"))
        assertTrue(json.contains("1700000000000"))
        assertTrue(json.contains("\"riskScore\": 70"))
        assertTrue(json.contains("\"issuesFound\": 3"))
    }
}
