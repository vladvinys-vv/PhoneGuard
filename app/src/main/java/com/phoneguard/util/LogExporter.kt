package com.phoneguard.util

import android.content.Context
import android.os.Environment
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

object LogExporter {

    suspend fun exportLogs(
        context: Context,
        blockedLogs: List<com.phoneguard.data.local.BlockedLog>,
        firewallLogs: List<FirewallLog>,
        scanHistory: List<ScanHistory>
    ): File? = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
