package com.phoneguard.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
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

    companion object {
        private const val KEY_ALIAS = "phoneguard_db_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "phoneguard_keystore_prefs"
        private const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_db_passphrase"
        private const val KEY_IV = "db_passphrase_iv"
    }

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

    private fun decryptPassphrase(encryptedPassphrase: String, ivString: String): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = android.util.Base64.decode(ivString, android.util.Base64.DEFAULT)
            val gcmParameterSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec)

            val encryptedBytes = android.util.Base64.decode(encryptedPassphrase, android.util.Base64.DEFAULT)
            cipher.doFinal(encryptedBytes)
        } catch (e: Exception) {
            throw RuntimeException("Failed to decrypt database passphrase", e)
        }
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

            prefs.edit()
                .putString(KEY_ENCRYPTED_PASSPHRASE, android.util.Base64.encodeToString(encryptedPassphrase, android.util.Base64.DEFAULT))
                .putString(KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.DEFAULT))
                .apply()

            passphrase
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate database passphrase", e)
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