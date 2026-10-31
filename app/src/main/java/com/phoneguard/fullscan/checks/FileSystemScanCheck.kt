package com.phoneguard.fullscan.checks

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.phoneguard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileSystemScanCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class FileScanResult(
        val suspiciousFiles: List<SuspiciousFile>,
        val totalFilesScanned: Int,
        val details: List<String>
    )
    data class SuspiciousFile(
        val path: String,
        val reason: String,
        val severity: Int
    )

    private val suspiciousExtensions = listOf("apk", "dex", "jar", "vbs", "ps1", "sh", "bat")

    fun performScan(): FileScanResult {
        val details = mutableListOf<String>()
        val suspiciousFiles = mutableListOf<SuspiciousFile>()
        var totalScanned = 0
        try {
            details.add(context.getString(R.string.file_scan_start_mediastore))
            // Раньше выборка сразу ограничивалась Download, а затем проверялось, что путь
            // НЕ содержит Download — условие не могло выполниться никогда. Теперь смотрим
            // все файлы и ищем APK, разложенные вне Download (типичная маскировка).
            val allFiles = queryMediaStore(MediaStore.Files.getContentUri("external"))
            totalScanned += allFiles.size
            allFiles.filter { it.extension.equals("apk", ignoreCase = true) }.forEach { file ->
                if (!isInsideDownloads(file.path)) {
                    suspiciousFiles.add(
                        SuspiciousFile(
                            file.path,
                            context.getString(R.string.file_scan_reason_apk_outside_downloads),
                            2
                        )
                    )
                }
            }
            details.add(context.getString(R.string.file_scan_start_dcim))
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            if (dcimDir?.exists() == true) {
                val nomediaDirs = findNomediaDirectories(dcimDir)
                nomediaDirs.forEach { dir ->
                    val filesInDir = dir.listFiles() ?: emptyArray()
                    if (filesInDir.any { it.extension.lowercase(Locale.ROOT) in suspiciousExtensions || it.canExecute() }) {
                        suspiciousFiles.add(
                            SuspiciousFile(
                                dir.absolutePath,
                                context.getString(R.string.file_scan_reason_hidden_nomedia),
                                3
                            )
                        )
                    }
                    totalScanned += filesInDir.size
                }
            }
        } catch (_: Exception) {}

        details.add("")
        details.add(context.getString(R.string.file_scan_limit_scoped_storage))
        details.add(context.getString(R.string.file_scan_limit_mediastore))
        details.add("")

        return FileScanResult(suspiciousFiles, totalScanned, details)
    }

    private fun queryMediaStore(
        uri: android.net.Uri,
        directory: String? = null
    ): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        try {
            val isScoped = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            val projection = if (isScoped) {
                arrayOf(
                    MediaStore.Files.FileColumns.RELATIVE_PATH,
                    // DISPLAY_NAME, а не TITLE: TITLE идёт без расширения, поэтому
                    // раньше `extension == "apk"` не срабатывал на Android 10+.
                    MediaStore.Files.FileColumns.DISPLAY_NAME
                )
            } else {
                arrayOf(MediaStore.Files.FileColumns.DATA)
            }
            val selection = if (isScoped && directory != null) {
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
            } else {
                null
            }
            val selectionArgs = if (isScoped && directory != null) arrayOf("$directory%") else null

            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )
            cursor?.use { c ->
                val relPathIndex = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                val displayNameIndex = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataIndex = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)

                while (c.moveToNext()) {
                    val path: String = if (isScoped) {
                        val relPath = c.getString(relPathIndex) ?: ""
                        val displayName = c.getString(displayNameIndex) ?: ""
                        if (relPath.isNotBlank() && displayName.isNotBlank()) {
                            "${Environment.getExternalStorageDirectory()}/$relPath$displayName"
                        } else {
                            continue
                        }
                    } else {
                        c.getString(dataIndex) ?: continue
                    }
                    files.add(MediaFile(path))
                }
            }
        } catch (_: Exception) {}
        return files
    }

    /** Лежит ли файл в публичном каталоге Download (или Downloads). */
    private fun isInsideDownloads(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        return DOWNLOAD_DIR_MARKERS.any { marker -> normalized.contains(marker) }
    }

    private fun findNomediaDirectories(dir: File, depth: Int = 0): List<File> {
        if (depth > MAX_DIRECTORY_DEPTH) return emptyList()
        val result = mutableListOf<File>()
        try {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    if (File(child, ".nomedia").exists()) result.add(child)
                    result.addAll(findNomediaDirectories(child, depth + 1))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private data class MediaFile(val path: String) {
        val extension: String get() = File(path).extension
    }

    private companion object {
        /** Каталоги-«песочницы» для скачанных APK. */
        val DOWNLOAD_DIR_MARKERS = listOf("/download/", "/downloads/")

        /** Ограничение рекурсии: защита от циклов символических ссылок. */
        const val MAX_DIRECTORY_DEPTH = 8
    }
}
