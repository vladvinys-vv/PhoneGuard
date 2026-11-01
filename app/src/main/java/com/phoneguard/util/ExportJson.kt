package com.phoneguard.util

import com.phoneguard.data.local.BlockedLog
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.ScanHistory

/**
 * Сериализация выгрузки журналов в JSON без `org.json`.
 *
 * Зачем свой сериализатор:
 *  * `LogExporter` собирал `JSONObject`, затем `JSONArray` и затем ещё и строку
 *    `json.toString(2)` — при выгрузке за 90 дней это несколько копий всех логов в
 *    памяти сразу (риск OOM на слабом устройстве). Здесь JSON пишется сразу в
 *    буфер файла;
 *  * `JSONObject.put(name, enum)` не документирует, как сериализуются enum'ы:
 *    `connectionType = WIFI` могло уехать в файл как `WIFI` без кавычек (невалидный
 *    JSON) либо уронить `toString()` с `JSONException`, и тогда выгрузка молча
 *    возвращала `null`. Теперь пишется `.name` в кавычках;
 *  * экранирование строк реализовано явно и покрыто JVM-тестами.
 *
 * Формат повторяет прежний: `{ "blockedLogs": [...], "firewallLogs": [...],
 * "scanHistory": [...] }`, отступ — два пробела.
 */
internal object ExportJson {

    /** Пишет весь документ в [out]; вызывающий код закрывает поток сам. */
    fun write(
        out: Appendable,
        blockedLogs: List<BlockedLog>,
        firewallLogs: List<FirewallLog>,
        scanHistory: List<ScanHistory>
    ) {
        out.append("{\n")

        out.append("  \"blockedLogs\": ")
        writeArray(out, blockedLogs) { log ->
            out.append("{\n")
            field(out, "phoneNumber", quote(log.phoneNumber))
            out.append(",\n")
            field(out, "isSms", log.isSms.toString())
            out.append(",\n")
            field(out, "timestamp", log.timestamp.toString())
            out.append("\n      }")
        }
        out.append(",\n")

        out.append("  \"firewallLogs\": ")
        writeArray(out, firewallLogs) { log ->
            out.append("{\n")
            field(out, "packageName", quote(log.packageName))
            out.append(",\n")
            field(out, "appName", quote(log.appName))
            out.append(",\n")
            field(out, "ipAddress", log.ipAddress.quoteOrNull())
            out.append(",\n")
            field(out, "domainName", log.domainName.quoteOrNull())
            out.append(",\n")
            field(out, "timestamp", log.timestamp.toString())
            out.append(",\n")
            // Отсюда и до конца — enum'ы: пишем имена явно, а не объект enum.
            field(out, "connectionType", quote(log.connectionType.name))
            out.append(",\n")
            field(out, "trafficDirection", quote(log.trafficDirection.name))
            out.append("\n      }")
        }
        out.append(",\n")

        out.append("  \"scanHistory\": ")
        writeArray(out, scanHistory) { scan ->
            out.append("{\n")
            field(out, "timestamp", scan.timestamp.toString())
            out.append(",\n")
            field(out, "riskScore", scan.riskScore.toString())
            out.append(",\n")
            field(out, "issuesFound", scan.issuesFound.toString())
            out.append(",\n")
            field(out, "reportJson", quote(scan.reportJson))
            out.append("\n      }")
        }
        out.append("\n}\n")
    }

    /**
     * Строковый литерал JSON (RFC 8259): экранируются кавычка, обратная косая черта
     * и все управляющие символы. Не-ASCII (немецкие умлауты, кириллица) остаются
     * как есть — файл пишется в UTF-8.
     */
    fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else ->
                    if (char < ' ') {
                        builder.append("\\u")
                        builder.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(char)
                    }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private fun String?.quoteOrNull(): String = if (this == null) "null" else quote(this)

    private fun field(out: Appendable, name: String, rawValue: String) {
        out.append("        \"").append(name).append("\": ").append(rawValue)
    }

    private inline fun <T> writeArray(out: Appendable, items: List<T>, writeItem: (T) -> Unit) {
        if (items.isEmpty()) {
            out.append("[]")
            return
        }
        out.append("[\n      ")
        items.forEachIndexed { index, item ->
            if (index > 0) out.append(",\n      ")
            writeItem(item)
        }
        out.append("\n  ]")
    }
}
