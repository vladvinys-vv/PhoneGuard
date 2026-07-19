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
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val keyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private val vaultDir by lazy {
        File(context.filesDir, "vault").apply { mkdirs() }
    }

    suspend fun encryptFileToVault(
        inputUri: Uri,
        deleteOriginal: Boolean = false
    ): VaultItem = withContext(Dispatchers.IO) {
        val fileName = getFileName(inputUri)
        val fileSize = getFileSize(inputUri)
        val mimeType = context.contentResolver.getType(inputUri) ?: "application/octet-stream"

        val encryptedFile = createEncryptedFile(fileName)
        val outputStream: OutputStream = encryptedFile.openFileOutput()
        val inputStream: InputStream = context.contentResolver.openInputStream(inputUri)!!

        inputStream.copyTo(outputStream)
        outputStream.flush()
        outputStream.close()
        inputStream.close()

        if (deleteOriginal) {
            try {
                context.contentResolver.delete(inputUri, null, null)
            } catch (_: Exception) {}
        }

        VaultItem(
            fileName = fileName,
            fileSize = fileSize,
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

    fun decryptFileFromVault(item: VaultItem): File? {
        val encryptedFile = createEncryptedFile(item.fileName)
        val outputFile = File.createTempFile("decrypted_", ".tmp", context.cacheDir)
        outputFile.deleteOnExit()
        val inputStream = encryptedFile.openFileInput()
        val outputStream = outputFile.outputStream()

        inputStream.copyTo(outputStream)
        outputStream.close()
        inputStream.close()

        return outputFile
    }

    fun cleanupDecryptedFile(file: File) {
        file.delete()
    }

    fun deleteVaultFile(item: VaultItem) {
        val file = File(vaultDir, item.fileName)
        file.delete()
    }

    private fun createEncryptedFile(fileName: String): EncryptedFile {
        return EncryptedFile.Builder(
            File(vaultDir, fileName),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "file_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex) ?: fileName
            }
        }
        return sanitizeFileName(fileName)
    }

    private fun sanitizeFileName(name: String): String {
        val withoutPath = name.replace(File.separator, "_").replace("..", "_")
        val withoutControl = withoutPath.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return withoutControl.take(100).ifBlank { "file_${System.currentTimeMillis()}" }
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                size = cursor.getLong(sizeIndex)
            }
        }
        return size
    }
}
