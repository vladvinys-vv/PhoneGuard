package com.phoneguard.fullscan.checks

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
            details.add("Сканирование Downloads через MediaStore...")
            val downloadFiles = queryMediaStore(
                MediaStore.Files.getContentUri("external"),
                Environment.DIRECTORY_DOWNLOADS
            )
            totalScanned += downloadFiles.size
            downloadFiles.filter { it.extension.equals("apk", ignoreCase = true) }.forEach { file ->
                if (!file.path.contains(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true)) {
                    suspiciousFiles.add(SuspiciousFile(file.path, "APK вне папки Download", 2))
                }
            }
            details.add("Сканирование DCIM...")
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            if (dcimDir?.exists() == true) {
                val nomediaDirs = findNomediaDirectories(dcimDir)
                nomediaDirs.forEach { dir ->
                    val filesInDir = dir.listFiles() ?: emptyArray()
                    if (filesInDir.any { it.extension.lowercase() in suspiciousExtensions || it.canExecute() }) {
                        suspiciousFiles.add(SuspiciousFile(dir.absolutePath,
                            "Скрытая .nomedia директория с подозрительными файлами", 3))
                    }
                    totalScanned += filesInDir.size
                }
            }
        } catch (_: Exception) {}

        details.add("")
        details.add("— Ограничение: Scoped Storage (Android 10+) ограничивает доступ.")
        details.add("— Сканирование через MediaStore API, приватные директории недоступны.")
        details.add("")

        return FileScanResult(suspiciousFiles, totalScanned, details)
    }

    private fun queryMediaStore(uri: android.net.Uri, directory: String): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        try {
            val projection: Array<String>
            val selection: String?
            val selectionArgs: Array<String>?

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                projection = arrayOf(
                    MediaStore.Files.FileColumns.RELATIVE_PATH,
                    MediaStore.Files.FileColumns.TITLE
                )
                selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf("$directory%")
            } else {
                projection = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.TITLE)
                selection = null
                selectionArgs = null
            }

            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use { c ->
                val relPathIndex = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                val titleIndex = c.getColumnIndex(MediaStore.Files.FileColumns.TITLE)
                val dataIndex = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)

                while (c.moveToNext()) {
                    val path: String = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val relPath = c.getString(relPathIndex) ?: ""
                        val title = c.getString(titleIndex) ?: ""
                        if (relPath.isNotBlank() && title.isNotBlank()) {
                            "${Environment.getExternalStorageDirectory()}/$relPath$title"
                        } else {
                            continue
                        }
                    } else {
                        c.getString(dataIndex) ?: continue
                    }
                    val file = File(path)
                    files.add(MediaFile(path, file.name, file.extension))
                }
            }
        } catch (_: Exception) {}
        return files
    }

    private fun findNomediaDirectories(dir: File): List<File> {
        val result = mutableListOf<File>()
        try {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    if (File(child, ".nomedia").exists()) result.add(child)
                    if (child.name != "." && child.name != "..")
                        result.addAll(findNomediaDirectories(child))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private data class MediaFile(val path: String, val name: String, val extension: String)
}
