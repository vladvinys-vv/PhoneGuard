package com.phoneguard.util

import com.phoneguard.model.FullScanReport
import com.phoneguard.model.ScanIssue

/**
 * JSON-представление отчёта полного сканирования — без Gson и без рефлексии.
 *
 * Раньше отчёт сохранялся как `Gson().toJson(report)`. Это работало в debug и ломалось в
 * релизе: R8 переименовывает поля Kotlin-классов, поэтому в `scan_history.report_json`
 * уезжали ключи вида `{"a":50,"b":[…]}`, а не `riskScore`/`issues`. Спасал только
 * широкий `-keep class com.phoneguard.model.**`, то есть отчётность зависела от строки в
 * proguard-файле, которую легко удалить «для оптимизации».
 *
 * Здесь ключи — строковые литералы, поэтому результат одинаков в debug и в release, порядок
 * полей фиксирован, а экранирование общее с выгрузкой журналов ([ExportJson.quote]).
 */
internal object ScanReportJson {

    fun toJson(report: FullScanReport): String = buildString {
        append('{')
        appendField("riskScore", report.riskScore.toString())
        append(',')
        appendField("criticalCount", report.criticalCount.toString())
        append(',')
        appendField("warningCount", report.warningCount.toString())
        append(',')
        appendField("infoCount", report.infoCount.toString())
        append(',')
        append("\"issues\":[")
        report.issues.forEachIndexed { index, issue ->
            if (index > 0) append(',')
            append(issue(issue))
        }
        append("]}")
    }

    private fun issue(issue: ScanIssue): String = buildString {
        append('{')
        // enum пишется именем: `JSONObject.put(enum)` уже однажды уехал в файл без кавычек.
        appendField("severity", ExportJson.quote(issue.severity.name))
        append(',')
        appendField("title", ExportJson.quote(issue.title))
        append(',')
        appendField("description", ExportJson.quote(issue.description))
        append(',')
        appendField("recommendation", ExportJson.quote(issue.recommendation))
        append(',')
        appendField("isHeuristic", issue.isHeuristic.toString())
        append(',')
        appendField("isPlaceholder", issue.isPlaceholder.toString())
        append('}')
    }

    private fun StringBuilder.appendField(name: String, rawValue: String) {
        append('"').append(name).append("\":").append(rawValue)
    }
}
