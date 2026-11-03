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
        File(context.cacheDir, PREVIEW_DIR_NAME).apply {
            mkdirs()
            // Копии прошлой сессии: приложение могли убить до того, как сработала уборка.
            prunePreviewDir(keepName = null)
        }
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

        val category = VaultItemKind.categoryOf(mimeType)

        VaultItem(
            fileName = fileName,
            fileSize = written,
            mimeType = mimeType,
            encryptionKeyAlias = masterKey.keyAlias,
            // `isImage` раньше не заполнялся вовсе: в базу всегда уходило `false`.
            isImage = category == VaultItemCategory.IMAGE,
            category = category
        )
    }

    /**
     * Расшифровывает файл во временный файл внутри [previewDir].
     *
     * В кэше держится ограниченное число копий ([VaultPreviewPolicy.MAX_PREVIEW_FILES]),
     * старые удаляются при каждой расшифровке, а копии прошлых сессий подчищаются при
     * первом обращении к каталогу предпросмотра.
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
        val outputFile = File.createTempFile("decrypted_", ".tmp", previewDir)
        return try {
            encryptedFile.openFileInput().use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            // Уборка — после записи: только что созданную копию просмотрщик уже мог открыть,
            // удалять её нельзя (правила — в чистом [VaultPreviewPolicy]).
            prunePreviewDir(keepName = outputFile.name)
            outputFile
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }
    }

    /**
     * Оставляет в кэше предпросмотра ограниченное число свежих копий.
     *
     * @param keepName копия, которую нельзя удалять (только что расшифрованная), или `null`
     */
    private fun prunePreviewDir(keepName: String?) {
        val entries = previewDir.listFiles()
            ?.map { file -> VaultPreviewPolicy.Entry(file.name, file.lastModified()) }
            .orEmpty()
        val now = System.currentTimeMillis()
        VaultPreviewPolicy.toDelete(entries, now, keepName).forEach { entry ->
            runCatching { File(previewDir, entry.name).delete() }
        }
    }

    /**
     * Удаляет расшифрованную копию из кэша предпросмотра.
     *
     * Удаляется только файл внутри [previewDir]: путь приходит извне, и
     * `delete()` по произвольному `File` был бы удалением чужих данных.
     */
    fun cleanupDecryptedFile(file: File) {
        // Удаляем только непосредственного ребёнка каталога предпросмотра: правило —
        // в чистом [VaultFileNames.isDirectChildOf] (путь приходит извне).
        if (!VaultFileNames.isDirectChildOf(previewDir, file)) return
        runCatching { file.canonicalFile.delete() }
    }

    /** Виден ли путь как «внутри каталога сейфа» — используется и проверкой, и тестами. */
    internal fun isInsideVault(path: java.io.File): Boolean {
        val root = runCatching { vaultDir.canonicalPath }.getOrNull() ?: return false
        val target = runCatching { path.canonicalPath }.getOrNull() ?: return false
        return VaultFileNames.isInside(root, target)
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
        if (!isInsideVault(file)) return null
        return file.takeIf { it.exists() }
    }

    private fun createEncryptedFile(fileName: String): EncryptedFile {
        return EncryptedFile.Builder(
            File(vaultDir, fileName),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /** Само копирование и лимит — в чистом [VaultStreams] (там же его тесты). */
    private fun copyWithLimit(input: InputStream, output: java.io.OutputStream, maxBytes: Long): Long =
        VaultStreams.copyWithLimit(input, output, maxBytes)

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
    internal fun sanitizeFileName(name: String): String =
        VaultFileNames.sanitize(name, fallback = "file_${System.currentTimeMillis()}")

    /** Добавляет суффикс, если файл с таким именем уже лежит в сейфе. */
    private fun uniqueFileName(fileName: String): String =
        VaultFileNames.unique(fileName) { candidate -> File(vaultDir, candidate).exists() }

    companion object {
        /**
         * Лимит размера файла для шифрования в хранилище.
         *
         * Единственный источник значения: `VaultViewModel.MAX_FILE_SIZE_BYTES`
         * ссылается сюда, поэтому проверка в UI и в `EncryptionManager` не могут
         * разойтись.
         */
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024

        /** Сколько расшифрованная копия живёт в кэше предпросмотра (см. [VaultPreviewPolicy]). */
        const val PREVIEW_MAX_AGE_MS = VaultPreviewPolicy.PREVIEW_MAX_AGE_MS

        /** Должно совпадать с `<cache-path>` в `res/xml/file_paths.xml`. */
        const val PREVIEW_DIR_NAME = "vault_preview"
    }
}
