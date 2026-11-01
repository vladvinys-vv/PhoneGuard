package com.phoneguard.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.phoneguard.model.VaultItem
import com.phoneguard.model.VaultItemCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Файл больше лимита сейфа.
 *
 * Отдельный тип нужен, чтобы UI показал «файл слишком большой», а не сырое
 * сообщение исключения (раньше туда попадала русская строка из кода, и в
 * английской/немецкой локали ошибка была на русском).
 */
class VaultFileTooLargeException : IOException("Vault entry exceeds the size limit")

/** Провайдер не отдал поток: файл удалён или доступ отозван. */
class VaultSourceUnavailableException(uri: Uri) :
    IOException("Cannot open input stream for $uri")

/**
 * Шифрование файлов сейфа через Jetpack Security (`EncryptedFile`, AES-256-GCM)
 * и ключ в AndroidKeyStore.
 *
 * Что было не так раньше:
 *  - имя файла чистилось регуляркой `[^a-zA-Z0-9._-]`, поэтому «Семейное фото.jpg»
 *    превращалось в `______.jpg`, а два файла с кириллическими именами получали
 *    одно и то же имя и перезаписывали друг друга;
 *  - `openInputStream(uri)!!` падал с NPE, если провайдер отзывал доступ;
 *  - поток не закрывался при исключении;
 *  - размер файла не проверялся при копировании, поэтому лимит в UI обходился.
 */
@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val vaultDir by lazy {
        File(context.filesDir, "vault").apply { mkdirs() }
    }

    /**
     * Каталог для расшифрованного предпросмотра. Отдельная подпапка (а не весь
     * `cacheDir`), потому что именно она открыта наружу через `FileProvider` —
     * см. `res/xml/file_paths.xml`.
     */
    private val previewDir by lazy {
        File(context.cacheDir, PREVIEW_DIR_NAME).apply { mkdirs() }
    }

    suspend fun encryptFileToVault(
        inputUri: Uri,
        deleteOriginal: Boolean = false,
        maxBytes: Long = MAX_FILE_SIZE_BYTES
    ): VaultItem = withContext(Dispatchers.IO) {
        val displayName = getFileName(inputUri)
        val fileName = uniqueFileName(displayName)
        val mimeType = context.contentResolver.getType(inputUri) ?: "application/octet-stream"

        var written = 0L
        val encryptedFile = createEncryptedFile(fileName)
        try {
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                encryptedFile.openFileOutput().use { output ->
                    written = copyWithLimit(input, output, maxBytes)
                }
            } ?: throw VaultSourceUnavailableException(inputUri)
        } catch (e: Exception) {
            // Не оставляем в сейфе недописанный (и, возможно, испорченный) файл.
            File(vaultDir, fileName).delete()
            throw e
        }

        if (deleteOriginal) {
            runCatching { context.contentResolver.delete(inputUri, null, null) }
        }

        VaultItem(
            fileName = fileName,
            fileSize = written,
            mimeType = mimeType,
            encryptionKeyAlias = masterKey.keyAlias,
            category = when {
                mimeType.startsWith("image/") -> VaultItemCategory.IMAGE
                mimeType.startsWith("video/") -> VaultItemCategory.VIDEO
                mimeType.startsWith("application/pdf") || mimeType.startsWith("text/") -> VaultItemCategory.DOCUMENT
                else -> VaultItemCategory.OTHER
            }
        )
    }

    /**
     * Расшифровывает файл во временный файл внутри [previewDir].
     *
     * Предыдущий предпросмотр удаляется: в кэше никогда не лежит больше одного
     * открытого файла, и он не переживает перезапуск в большом количестве копий.
     */
    fun decryptFileFromVault(item: VaultItem): File? {
        // Имя файла приходит из БД: без проверки путь вида `../../databases/…`
        // вывел бы расшифровку за пределы каталога сейфа.
        val source = existingVaultFileOrNull(item.fileName) ?: return null
        val encryptedFile = EncryptedFile.Builder(
            source,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        // Удаляем только устаревшие копии: файл, открытый во внешнем просмотрщике
        // секунду назад, удалять нельзя — просмотрщик получит «файл исчез».
        val staleBefore = System.currentTimeMillis() - PREVIEW_MAX_AGE_MS
        previewDir.listFiles()?.forEach { stale -> if (stale.lastModified() < staleBefore) stale.delete() }
        val outputFile = File.createTempFile("decrypted_", ".tmp", previewDir)
        return try {
            encryptedFile.openFileInput().use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            outputFile
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }
    }

    /**
     * Удаляет расшифрованную копию из кэша предпросмотра.
     *
     * Удаляется только файл внутри [previewDir]: путь приходит извне, и
     * `delete()` по произвольному `File` был бы удалением чужих данных.
     */
    fun cleanupDecryptedFile(file: File) {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        val root = runCatching { previewDir.canonicalPath }.getOrNull() ?: return
        if (target.parentFile?.path != root) return
        target.delete()
    }

    fun deleteVaultFile(item: VaultItem) {
        existingVaultFileOrNull(item.fileName)?.delete()
    }

    /**
     * Файл сейфа по имени из БД, только если он действительно лежит внутри
     * каталога сейфа. `null` — имя подозрительное или файла нет.
     */
    private fun existingVaultFileOrNull(fileName: String): File? {
        if (fileName.isBlank()) return null
        val file = File(vaultDir, fileName)
        val vaultPath = runCatching { vaultDir.canonicalPath }.getOrNull() ?: return null
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (!filePath.startsWith(vaultPath + File.separator)) return null
        return file.takeIf { it.exists() }
    }

    private fun createEncryptedFile(fileName: String): EncryptedFile {
        return EncryptedFile.Builder(
            File(vaultDir, fileName),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    private fun copyWithLimit(input: InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw VaultFileTooLargeException()
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun getFileName(uri: Uri): String {
        var fileName: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
        }
        return sanitizeFileName(fileName ?: "file_${System.currentTimeMillis()}")
    }

    /**
     * Оставляет буквы любого алфавита, цифры, точку, дефис и подчёркивание.
     * Отбрасываются только разделители пути и управляющие символы — имена
     * пользовательских файлов не обязаны быть латиницей.
     */
    internal fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .map { ch -> if (ch.isISOControl() || ch in FORBIDDEN_CHARS) '_' else ch }
            .joinToString("")
            .trim()
            .trimStart('.')
            .take(MAX_NAME_LENGTH)
        return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
    }

    /** Добавляет суффикс, если файл с таким именем уже лежит в сейфе. */
    private fun uniqueFileName(fileName: String): String {
        if (!File(vaultDir, fileName).exists()) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$base($counter)$extension"
            if (!File(vaultDir, candidate).exists()) return candidate
            counter++
        }
    }

    companion object {
        /**
         * Лимит размера файла для шифрования в хранилище.
         *
         * Единственный источник значения: `VaultViewModel.MAX_FILE_SIZE_BYTES`
         * ссылается сюда, поэтому проверка в UI и в `EncryptionManager` не могут
         * разойтись.
         */
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024

        private const val MAX_NAME_LENGTH = 100

        /** Сколько расшифрованная копия живёт в кэше предпросмотра. */
        const val PREVIEW_MAX_AGE_MS = 10 * 60 * 1000L

        /** Должно совпадать с `<cache-path>` в `res/xml/file_paths.xml`. */
        const val PREVIEW_DIR_NAME = "vault_preview"
        private val FORBIDDEN_CHARS = setOf(':', '*', '?', '"', '<', '>', '|', '\u0000')
    }
}
