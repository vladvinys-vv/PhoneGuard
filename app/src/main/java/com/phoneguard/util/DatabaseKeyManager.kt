package com.phoneguard.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Ошибка работы с ключом БД.
     *
     * Сообщения — технические и намеренно на английском: они попадают в лог и
     * crash-report, а не в UI (пользователю показывается локализованный текст).
     */
    class DatabaseKeyException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    companion object {
        private const val KEY_ALIAS = "phoneguard_db_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "phoneguard_keystore_prefs"
        private const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_db_passphrase"
        private const val KEY_IV = "db_passphrase_iv"
        /** Длина IV для AES-GCM в байтах (тот же размер использует провайдер). */
        private const val MIN_GCM_IV_LENGTH = 12
        private const val MAX_GCM_IV_LENGTH = 16

        /** Имя файла базы: нужно, чтобы удалить нерасшифровываемую базу. */
        const val DATABASE_NAME = "phoneguard_db"
    }

    /**
     * Парольная фраза БД.
     *
     * `@Synchronized` обязателен: без него два потока при первом запуске могли
     * сгенерировать **разные** фразы, одна из них перетирала другую в prefs, и
     * база, открытая с первой фразой, становилась нечитаемой при следующем старте.
     */
    @Synchronized
    fun getDatabasePassphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val encryptedPassphrase = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        val ivString = prefs.getString(KEY_IV, null)

        return if (encryptedPassphrase != null && ivString != null) {
            decryptPassphrase(encryptedPassphrase, ivString)
        } else {
            generateAndStorePassphrase(prefs)
        }
    }

    /**
     * Расшифровывает парольную фразу БД ключом из AndroidKeyStore.
     *
     * Раньше исключение заворачивалось в `RuntimeException` без типа, а ключ
     * доставался жёстким приведением `as KeyStore.SecretKeyEntry` — при
     * восстановлении бэкапа на другом устройстве (когда ключ отсутствует)
     * приложение падало с невнятным `ClassCastException`/`NullPointerException`.
     * Теперь причина явно отделена от прочих сбоев БД.
     */
    private fun decryptPassphrase(encryptedPassphrase: String, ivString: String): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val entry = keyStore.getEntry(KEY_ALIAS, null)
                ?: throw DatabaseKeyException(
                    "Database key is missing from the AndroidKeyStore — data restored from a backup is unreadable"
                )
            val secretKey = (entry as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw DatabaseKeyException("Unexpected database key type in the AndroidKeyStore")

            val iv = android.util.Base64.decode(ivString, android.util.Base64.DEFAULT)
            if (iv.size !in MIN_GCM_IV_LENGTH..MAX_GCM_IV_LENGTH) {
                throw DatabaseKeyException("Invalid database key IV (${iv.size} bytes)")
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

            val encryptedBytes = android.util.Base64.decode(encryptedPassphrase, android.util.Base64.DEFAULT)
            cipher.doFinal(encryptedBytes)
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyException("Failed to decrypt the database passphrase", e)
        }
    }

    /**
     * Крайняя мера: ключ утерян (бэкап восстановлен на другом устройстве, сброшен
     * AndroidKeyStore, повреждена запись в prefs) — пересоздаём фразу и удаляем
     * нерасшифровываемую базу.
     *
     * Альтернатива — падать при каждом запуске, и тогда единственным выходом для
     * пользователя остаётся «Очистить данные», который удаляет ту же базу. Так что
     * поведение эквивалентно, но приложение продолжает работать, а журналы событий
     * (единственное, что теряется) и так были недоступны.
     */
    @Synchronized
    fun resetWithNewPassphrase(): ByteArray {
        deleteDatabaseFiles()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_ENCRYPTED_PASSPHRASE)
            .remove(KEY_IV)
            .commit()
        return generateAndStorePassphrase(prefs)
    }

    private fun deleteDatabaseFiles() {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm"), File("${dbFile.path}-journal"))
            .forEach { file -> runCatching { if (file.exists()) file.delete() } }
        // SQLCipher хранит «соль»/заголовок только в самом файле БД, отдельных sidecar нет.
    }

    private fun generateAndStorePassphrase(prefs: SharedPreferences): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = if (keyStore.containsAlias(KEY_ALIAS)) {
                (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                generateSecretKey()
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv

            val passphrase = ByteArray(32)
            java.security.SecureRandom().nextBytes(passphrase)

            val encryptedPassphrase = cipher.doFinal(passphrase)

            // commit(), а не apply(): если процесс умрёт между созданием БД и
            // асинхронной записью, пароль будет потерян и все зашифрованные
            // данные станут недоступны.
            val stored = prefs.edit()
                .putString(KEY_ENCRYPTED_PASSPHRASE, android.util.Base64.encodeToString(encryptedPassphrase, android.util.Base64.DEFAULT))
                .putString(KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.DEFAULT))
                .commit()
            if (!stored) {
                throw DatabaseKeyException("Failed to store the database passphrase")
            }

            passphrase
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyException("Failed to generate the database passphrase", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
}