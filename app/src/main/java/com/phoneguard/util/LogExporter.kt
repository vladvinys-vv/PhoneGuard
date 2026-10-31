package com.phoneguard.util

import android.content.Context
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.ScanHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт журналов в JSON-файл во внешнем каталоге приложения.
 *
 * Единственная реализация экспорта: `SettingsRepository.exportLogs()` раньше держал
 * свою копию, и они расходились (например, в одной не было `trafficDirection`).
 */
object LogExporter {

    suspend fun exportToJson(
        context: Context,
        blockedLogs: List<BlockedLog>,
        firewallLogs: List<FirewallLog>,
        scanHistory: List<ScanHistory>
    ): File? = withContext(Dispatchers.IO) {
        return@withContext exportInternal(context, blockedLogs, firewallLogs, scanHistory)
    }

    private fun exportInternal(
        context: Context,
        blockedLogs: List<BlockedLog>,
        firewallLogs: List<FirewallLog>,
        scanHistory: List<ScanHistory>
    ): File? {
        return try {
            // getExternalFilesDir() возвращает null, если внешнее хранилище недоступно
            // (оно может быть отмонтировано) — File(null, …) давал относительный путь,
            // запись в который молча падала.
            val externalDir = context.getExternalFilesDir(null) ?: return null
            val exportDir = File(externalDir, "exports")
            if (!exportDir.exists() && !exportDir.mkdirs()) return null

            // Locale.US: в некоторых локалях цифры в имени файла экзотические,
            // и файл потом неудобно искать/сортировать.
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(exportDir, "phoneguard_export_$timestamp.json")

            val json = JSONObject()
            val blockedArray = JSONArray()
            blockedLogs.forEach { log ->
                val obj = JSONObject()
                obj.put("phoneNumber", log.phoneNumber)
                obj.put("isSms", log.isSms)
                obj.put("timestamp", log.timestamp)
                blockedArray.put(obj)
            }
            json.put("blockedLogs", blockedArray)

            val firewallArray = JSONArray()
            firewallLogs.forEach { log ->
                val obj = JSONObject()
                obj.put("packageName", log.packageName)
                obj.put("appName", log.appName)
                obj.put("ipAddress", log.ipAddress)
                obj.put("domainName", log.domainName)
                obj.put("timestamp", log.timestamp)
                obj.put("connectionType", log.connectionType)
                obj.put("trafficDirection", log.trafficDirection)
                firewallArray.put(obj)
            }
            json.put("firewallLogs", firewallArray)

            val scanArray = JSONArray()
            scanHistory.forEach { scan ->
                val obj = JSONObject()
                obj.put("timestamp", scan.timestamp)
                obj.put("riskScore", scan.riskScore)
                obj.put("issuesFound", scan.issuesFound)
                obj.put("reportJson", scan.reportJson)
                scanArray.put(obj)
            }
            json.put("scanHistory", scanArray)

            file.writeText(json.toString(2))
            file
        } catch (e: Exception) {
            null
        }
    }
}