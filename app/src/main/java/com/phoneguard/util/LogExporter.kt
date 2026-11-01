package com.phoneguard.util

import android.content.Context
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.ScanHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        var file: File? = null
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
            val target = File(exportDir, "phoneguard_export_$timestamp.json")
            file = target

            // Пишем сразу в буфер файла: раньше JSON собирался в памяти
            // (JSONArray + JSONObject + toString(2)) — три копии всех логов.
            target.bufferedWriter().use { writer ->
                ExportJson.write(writer, blockedLogs, firewallLogs, scanHistory)
            }
            target
        } catch (e: Exception) {
            // Недописанный файл не оставляем: он выглядит как успешная выгрузка.
            runCatching { file?.delete() }
            null
        }
    }
}