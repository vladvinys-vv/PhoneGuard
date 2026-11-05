package com.phoneguard.data.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.util.SettingsTransfer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Экспорт и импорт настроек для переезда на новый телефон.
 *
 * Зачем это нужно: облачное резервное копирование выключено осознанно (`allowBackup=false`,
 * `dataExtractionRules` исключает и облако, и D2D), потому что в базе и сейфе лежат
 * чувствительные данные. Побочный эффект — при смене телефона пользователь терял все
 * настройки защиты: правила блокировки, брандмауэр, доверенный контакт, расписание
 * сканирований. Теперь их можно вынести в файл и перенести вручную.
 *
 * Формат и правила (что переносится, а что нет) — в чистом [SettingsTransfer]; здесь только
 * файлы и ContentResolver.
 */
@Singleton
class SettingsTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    /** Итог импорта для интерфейса. */
    sealed class ImportResult {
        /** @param accepted сколько настроек применено; @param rejected сколько отклонено и почему-то. */
        data class Applied(val accepted: Int, val rejected: Int) : ImportResult()

        /** Файл не прочитан или это не файл настроек PhoneGuard. */
        data class Failed(val reason: String?) : ImportResult()
    }

    /** Пишет настройки в `exports/` рядом с выгрузкой журналов. `null` — записать не удалось. */
    suspend fun exportToFile(): File? = withContext(Dispatchers.IO) {
        var file: File? = null
        try {
            // getExternalFilesDir() возвращает null, если внешнее хранилище недоступно.
            val externalDir = context.getExternalFilesDir(null) ?: return@withContext null
            val exportDir = File(externalDir, EXPORT_DIR)
            if (!exportDir.exists() && !exportDir.mkdirs()) return@withContext null

            // Locale.US: в некоторых локалях цифры в имени файла выглядят иначе и файл
            // потом неудобно искать.
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val target = File(exportDir, "phoneguard_settings_$timestamp.txt")
            file = target

            val entries = preferencesManager.exportableSettings()
            target.bufferedWriter().use { writer ->
                writer.write(SettingsTransfer.encode(entries))
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Cannot export settings", e)
            // Недописанный файл не оставляем: он выглядит как успешный экспорт.
            runCatching { file?.delete() }
            null
        }
    }

    /** Читает файл настроек по `uri` и применяет его. */
    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                // Файл настроек — единицы килобайт. Читать в память больше мегабайта
                // (случайно выбранный файл журнала или видео) незачем.
                val bytes = stream.readBytes()
                if (bytes.size > MAX_IMPORT_BYTES) {
                    return@withContext ImportResult.Failed(TOO_LARGE)
                }
                bytes.toString(Charsets.UTF_8)
            } ?: return@withContext ImportResult.Failed(NO_FILE)

            val bundle = SettingsTransfer.decode(text).getOrElse { error ->
                return@withContext ImportResult.Failed(error.message)
            }
            val plan = SettingsTransfer.planImport(bundle)
            val applied = preferencesManager.applyTransferredSettings(plan.accepted)
            // Пропущенные строки тоже считаем: пользователь должен видеть, что часть файла
            // не применилась, а не думать, что «всё перенеслось».
            ImportResult.Applied(
                applied = applied,
                rejected = plan.rejected.size + bundle.skippedLines
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot import settings", e)
            ImportResult.Failed(e.message)
        }
    }

    private companion object {
        const val TAG = "SettingsTransfer"

        /** Тот же каталог, что и у выгрузки журналов: пользователь ищет файлы в одном месте. */
        const val EXPORT_DIR = "exports"

        const val MAX_IMPORT_BYTES = 1_048_576

        const val TOO_LARGE = "file-too-large"
        const val NO_FILE = "cannot-open"
    }
}
