package com.phoneguard.util

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object SecurityUtils {
    fun hashPin(pin: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(pin.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: NoSuchAlgorithmException) {
            pin.hashCode().toString()
        }
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        return hashPin(pin) == storedHash
    }
}
