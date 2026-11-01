package com.phoneguard.fullscan.checks

import java.util.Locale

/**
 * Чистые правила файловой проверки (без `Context` и `MediaStore`).
 *
 * Вынесены из [FileSystemScanCheck], потому что именно здесь были ошибки,
 * которые нельзя было поймать тестом: проверка шла по `File`, а решение
 * принималось по строкам и флагам.
 */
internal object FileSystemHeuristics {

    /** Расширения, с которыми в «скрытом» каталоге лежит уже исполняемый код. */
    val SUSPICIOUS_EXTENSIONS = listOf("apk", "dex", "jar", "vbs", "ps1", "sh", "bat")

    /** Маркеры публичного каталога загрузок в нормализованном пути. */
    private val DOWNLOAD_DIR_MARKERS = listOf("/download/", "/downloads/")

    /** Элемент каталога — ровно то, что нужно для решения. */
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val canExecute: Boolean
    )

    /**
     * Подозрителен ли каталог с `.nomedia`.
     *
     * Раньше условие было `it.extension in SUSPICIOUS || it.canExecute()` и
     * проверялось на **всех** детях каталога. У каталогов на Android выставлен
     * бит выполнения (он нужен для обхода), поэтому любая папка с `.nomedia`,
     * внутри которой есть подпапка (кэш мессенджеров, галерей), объявлялась
     * «скрытым исполняемым содержимым» — полный скан показывал ложные находки.
     * Теперь каталоги не учитываются вовсе.
     */
    fun containsSuspiciousEntries(entries: List<Entry>): Boolean =
        entries.any { entry ->
            if (entry.isDirectory) {
                false
            } else {
                val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                extension in SUSPICIOUS_EXTENSIONS || entry.canExecute
            }
        }

    /**
     * Лежит ли файл в публичном каталоге загрузок.
     *
     * Разделители нормализуются, регистр не важен: путь из MediaStore на разных
     * прошивках приходит и как `/storage/emulated/0/Download/…`.
     */
    fun isInsideDownloads(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        return DOWNLOAD_DIR_MARKERS.any { marker -> normalized.contains(marker) }
    }
}
