package com.phoneguard.util

import com.phoneguard.model.FullScanReport
import com.phoneguard.model.IssueSeverity
import com.phoneguard.model.ScanIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отчёт сканирования пишется в `scan_history.report_json` и показывается пользователю.
 *
 * Раньше это делал `Gson().toJson(report)`: в release R8 переименовывает поля, и в базу уезжали
 * ключи вида `{"a":50}`. Теперь ключи — литералы, и они не должны меняться незаметно: этот тест
 * фиксирует и состав, и порядок, и экранирование.
 */
class ScanReportJsonTest {

    private fun issue(
        severity: IssueSeverity = IssueSeverity.WARNING,
        title: String = "Заголовок",
        description: String = "Описание",
        recommendation: String = "Рекомендация",
        isHeuristic: Boolean = false,
        isPlaceholder: Boolean = false
    ) = ScanIssue(severity, title, description, recommendation, isHeuristic, isPlaceholder)

    @Test
    fun `empty report keeps every counter and an empty issues array`() {
        val json = ScanReportJson.toJson(FullScanReport())
        assertEquals(
            "{\"riskScore\":0,\"criticalCount\":0,\"warningCount\":0,\"infoCount\":0,\"issues\":[]}",
            json
        )
    }

    @Test
    fun `report keys are literal names not reflection-based field names`() {
        val json = ScanReportJson.toJson(
            FullScanReport(riskScore = 50, criticalCount = 1, warningCount = 2, infoCount = 3)
        )
        assertTrue(json.contains("\"riskScore\":50"))
        assertTrue(json.contains("\"criticalCount\":1"))
        assertTrue(json.contains("\"warningCount\":2"))
        assertTrue(json.contains("\"infoCount\":3"))
        // Ни одного однобуквенного ключа, каким его сделал бы R8.
        assertFalse(json.contains("\"a\":"))
    }

    @Test
    fun `issue carries every field`() {
        val json = ScanReportJson.toJson(
            FullScanReport(issues = listOf(issue(isHeuristic = true, isPlaceholder = true)))
        )
        assertTrue(json.contains("\"severity\":\"WARNING\""))
        assertTrue(json.contains("\"title\":\"Заголовок\""))
        assertTrue(json.contains("\"description\":\"Описание\""))
        assertTrue(json.contains("\"recommendation\":\"Рекомендация\""))
        assertTrue(json.contains("\"isHeuristic\":true"))
        assertTrue(json.contains("\"isPlaceholder\":true"))
    }

    @Test
    fun `severity is written as a quoted name`() {
        val json = ScanReportJson.toJson(
            FullScanReport(issues = listOf(issue(severity = IssueSeverity.CRITICAL)))
        )
        // Без кавычек это уже невалидный JSON — так однажды уже случалось с JSONObject.put(enum).
        assertTrue(json.contains("\"severity\":\"CRITICAL\""))
    }

    @Test
    fun `quotes and control characters are escaped`() {
        val json = ScanReportJson.toJson(
            FullScanReport(
                issues = listOf(
                    issue(
                        title = "Кавычка \" и обратный слэш \\",
                        description = "первая\nвторая\tтретья"
                    )
                )
            )
        )
        assertTrue(json.contains("Кавычка \\\" и обратный слэш \\\\"))
        assertTrue(json.contains("первая\\nвторая\\tтретья"))
        // Сырых переводов строк внутри строкового литерала быть не может.
        assertFalse(json.contains("первая\nвторая"))
    }

    @Test
    fun `cyrillic and umlauts stay readable`() {
        val json = ScanReportJson.toJson(
            FullScanReport(issues = listOf(issue(title = "Проверка über"))))
        assertTrue(json.contains("\"Проверка über\""))
    }

    @Test
    fun `several issues are comma separated inside one array`() {
        val json = ScanReportJson.toJson(
            FullScanReport(issues = listOf(issue(title = "первый"), issue(title = "второй")))
        )
        assertTrue(json.contains("\"issues\":[{"))
        assertTrue(json.contains("},{"))
        assertEquals(2, json.split("\"title\"").size - 1)
    }

    @Test
    fun `same report always produces the same text`() {
        val report = FullScanReport(riskScore = 12, issues = listOf(issue()))
        assertEquals(ScanReportJson.toJson(report), ScanReportJson.toJson(report))
    }

    @Test
    fun `negative score is not clamped`() {
        // Оценка риска считается формулой и может уйти в минус при перевесе «безопасных» находок.
        assertTrue(ScanReportJson.toJson(FullScanReport(riskScore = -5)).contains("\"riskScore\":-5"))
    }

    @Test
    fun `braces and brackets are balanced`() {
        val json = ScanReportJson.toJson(
            FullScanReport(riskScore = 7, issues = listOf(issue(), issue(title = "x")))
        )
        var depth = 0
        var inString = false
        var escaped = false
        json.forEach { char ->
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' || char == '[' -> depth++
                char == '}' || char == ']' -> depth--
            }
            assertTrue("глубина не может стать отрицательной", depth >= 0)
        }
        assertEquals(0, depth)
        assertFalse(inString)
    }
}
