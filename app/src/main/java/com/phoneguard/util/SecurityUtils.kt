package com.phoneguard.util

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityUtils {
    private const val PBKDF2_ITERATIONS = 100_000
    private const val HASH_LENGTH = 256
    private const val SALT_LENGTH = 16

    fun hashPin(pin: String): String {
        val salt = generateSalt()
        val hash = pbkdf2(pin, salt)
        return "${base64Encode(salt)}:${base64Encode(hash)}"
    }

    fun verifyPin(pin: String, storedValue: String?): Boolean {
        if (storedValue == null) return false
        val parts = storedValue.split(":")
        if (parts.size != 2) return false

        return try {
            val salt = base64Decode(parts[0])
            val expectedHash = base64Decode(parts[1])
            val actualHash = pbkdf2(pin, salt)
            actualHash.contentEquals(expectedHash)
        } catch (_: Exception) {
            false
        }
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun base64Encode(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)

    private fun base64Decode(data: String): ByteArray =
        android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
}
