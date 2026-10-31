package com.phoneguard.data.settings

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.ScanHistory
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Экспорт журналов. Реализация одна — [LogExporter]; здесь только делегирование,
     * чтобы ViewModel не знал о деталях записи файла.
     */
    suspend fun exportLogs(
        blockedLogs: List<BlockedLog>,
        firewallLogs: List<FirewallLog>,
        scanHistory: List<ScanHistory>
    ): File? = LogExporter.exportToJson(context, blockedLogs, firewallLogs, scanHistory)

    /**
     * Удаляет файлы, созданные приложением: снимки с фронтальной камеры, приманки
     * honeypot, отчёты о падениях, экспортированные журналы и расшифрованные копии
     * из сейфа.
     *
     * Раньше «Удалить все данные» чистило только базу, поэтому снимки нарушителя,
     * стек-трейсы и приманки с «паролями» оставались на устройстве — пункт не
     * выполнял своё обещание.
     *
     * @return сколько файлов удалено (для журнала и тестов)
     */
    suspend fun deleteLocalFiles(): Int = withContext(Dispatchers.IO) {
        val externalDir = context.getExternalFilesDir(null)
        val directories = buildList {
            add(File(context.filesDir, CRASH_REPORTS_DIR))
            add(File(context.filesDir, HONEYPOT_DIR))
            add(File(context.cacheDir, VAULT_PREVIEW_DIR))
            if (externalDir != null) {
                add(File(externalDir, STEALTH_PHOTO_DIR))
                add(File(externalDir, "exports"))
            }
        }
        var deleted = 0
        directories.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) deleted++
            }
        }
        deleted
    }

    private companion object {
        /** Совпадает с `CrashHandler.CRASH_DIR`. */
        const val CRASH_REPORTS_DIR = "crash-reports"

        /** Совпадает с `HoneypotManager.DIR_NAME`. */
        const val HONEYPOT_DIR = "phoneguard_honeypot"

        /** Совпадает с `EncryptionManager.PREVIEW_DIR_NAME`. */
        const val VAULT_PREVIEW_DIR = "vault_preview"

        /** Совпадает с `StealthCameraService.OUTPUT_DIR`. */
        const val STEALTH_PHOTO_DIR = "phoneguard_stealth"
    }
}
