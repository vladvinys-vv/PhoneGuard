package com.phoneguard.data.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.phoneguard.data.local.VaultDao
import com.phoneguard.vault.EncryptionManager
import com.phoneguard.vault.VaultArchiveFormat
import com.phoneguard.vault.VaultReadFailure
import com.phoneguard.vault.VaultReadSuccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Перенос сейфа на новый телефон.
 *
 * Проблема, которую это решает: файлы сейфа зашифрованы ключом AndroidKeyStore, а он
 * не покидает устройство. Скопировать каталог `filesDir/vault` бессмысленно — на новом
 * телефоне ключа нет, и файлы не открыть. До этого при смене телефона сейф терялся
 * целиком (настройки к этому моменту уже переносились через `SettingsTransferManager`).
 *
 * Теперь сейф выгружается в один файл `*.pgvault`, зашифрованный **парольной фразой
 * пользователя** ([VaultArchiveFormat]): его можно положить на флешку, в облако или
 * переслать себе. Парольная фраза нигде не сохраняется — потеряли её, файл не открыть,
 * и это осознанное свойство, а не недоработка: иначе архив с сейфом был бы бесполезен
 * как защита.
 */
@Singleton
class VaultTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultDao: VaultDao,
    private val encryptionManager: EncryptionManager
) {

    /** Итог выгрузки. */
    sealed class ExportOutcome {
        /** @param items число файлов; @param file куда записан архив. */
        data class VaultExportDone(val items: Int, val file: File) : ExportOutcome()

        /** В сейфе нет файлов — переносить нечего. */
        object VaultExportEmpty : ExportOutcome()

        /** Парольная фраза не принята до начала работы. */
        data class VaultExportRefused(val reason: VaultArchiveFormat.Rejection) : ExportOutcome()

        /** Не удалось прочитать файл сейфа или записать архив. */
        object VaultExportFailed : ExportOutcome()
    }

    /** Итог восстановления. */
    sealed class ImportOutcome {
        /** @param items сколько файлов добавлено в сейф. */
        data class VaultImportDone(val items: Int) : ImportOutcome()

        /** Архив отклонён: чужая парольная фраза, другой формат, обрезанный файл. */
        data class VaultImportRefused(val reason: VaultArchiveFormat.Rejection) : ImportOutcome()

        /** Файл не открылся или сейф не смог записать: без подробностей для UI. */
        object VaultImportFailed : ImportOutcome()
    }

    /**
     * Пишет архив сейфа в `exports/` — рядом с выгрузкой журналов и настроек.
     *
     * @return [ExportOutcome]; `passphrase` вызывающий код обнуляет сам
     */
    suspend fun export(passphrase: CharArray): ExportOutcome = withContext(Dispatchers.IO) {
        VaultArchiveFormat.rejectionForPassphrase(passphrase)?.let { reason ->
            return@withContext ExportOutcome.VaultExportRefused(reason)
        }
        try {
            val items = vaultDao.getAllItems().first()
            if (items.isEmpty()) return@withContext ExportOutcome.VaultExportEmpty

            val externalDir = context.getExternalFilesDir(null)
                ?: return@withContext ExportOutcome.VaultExportFailed
            val exportDir = File(externalDir, EXPORT_DIR)
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                return@withContext ExportOutcome.VaultExportFailed
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val target = File(exportDir, "phoneguard_vault_$timestamp.pgvault")
            // Записи идут в том же порядке, что и в списке сейфа: свежие сверху.
            val sources = items.map { item ->
                VaultArchiveFormat.Source(
                    name = item.fileName,
                    mimeType = item.mimeType,
                    createdAt = item.createdAt,
                    openStream = { encryptionManager.openDecryptedStream(item) }
                )
            }
            try {
                target.outputStream().use { output ->
                    VaultArchiveFormat.write(output, passphrase, sources)
                }
            } catch (e: Exception) {
                // Недописанный архив выглядит как успешная выгрузка — удаляем.
                Log.w(TAG, "Cannot write vault archive", e)
                runCatching { target.delete() }
                return@withContext ExportOutcome.VaultExportFailed
            }
            ExportOutcome.VaultExportDone(items.size, target)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot export vault", e)
            ExportOutcome.VaultExportFailed
        }
    }

    /**
     * Восстанавливает файлы из архива в текущий сейф.
     *
     * Архив сначала расшифровывается в кэш целиком: тег каждой записи проверяется в конце
     * записи, поэтому «наполовину импортированный» сейф после чужой парольной фразы — это
     * ровно то, чего быть не должно. Только после успешной проверки всех записей файлы
     * шифруются ключом этого устройства и попадают в базу.
     *
     * Совпадения имён не перезаписываются: `EncryptionManager` добавляет суффикс, поэтому
     * повторный импорт того же архива создаёт копии, а не уничтожает старые файлы.
     */
    suspend fun import(uri: Uri, passphrase: CharArray): ImportOutcome = withContext(Dispatchers.IO) {
        VaultArchiveFormat.rejectionForPassphrase(passphrase)?.let { reason ->
            return@withContext ImportOutcome.VaultImportRefused(reason)
        }
        val stagingDir = File(context.cacheDir, STAGING_DIR)
        // Хвосты прошлого запуска: приложение могли убить до уборки.
        runCatching { stagingDir.deleteRecursively() }
        if (!stagingDir.mkdirs()) return@withContext ImportOutcome.VaultImportFailed

        val staged = mutableListOf<Pair<VaultArchiveFormat.Meta, File>>()
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportOutcome.VaultImportFailed

            val result = stream.use { input ->
                VaultArchiveFormat.read(input, passphrase) { meta ->
                    // Расшифрованный файл живёт только до конца импорта (см. finally).
                    val temp = File(stagingDir, "entry_${staged.size}.bin")
                    staged.add(meta to temp)
                    temp.outputStream()
                }
            }

            when (result) {
                is VaultReadFailure -> ImportOutcome.VaultImportRefused(result.reason)
                is VaultReadSuccess -> {
                    var imported = 0
                    staged.forEach { (meta, temp) ->
                        temp.inputStream().use { input ->
                            val item = encryptionManager.encryptStreamToVault(
                                displayName = meta.name,
                                mimeType = meta.mimeType,
                                createdAt = meta.createdAt,
                                input = input
                            )
                            vaultDao.insertItem(item)
                        }
                        imported++
                    }
                    ImportOutcome.VaultImportDone(imported)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot import vault archive", e)
            ImportOutcome.VaultImportFailed
        } finally {
            // Расшифрованные файлы в кэше — самое чувствительное, что здесь есть.
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private companion object {
        const val TAG = "VaultTransfer"

        /** Тот же каталог, что у выгрузки журналов и настроек. */
        const val EXPORT_DIR = "exports"

        /** Промежуточные расшифрованные файлы; удаляются в любом исходе импорта. */
        const val STAGING_DIR = "vault_transfer"
    }
}
