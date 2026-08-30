package com.phoneguard.util

import java.util.regex.Pattern

object PatternRules {

    fun matchesPattern(phoneNumber: String, pattern: String): Boolean {
        if (pattern.isBlank()) return false

        val regex = pattern
            .replace(".", "\\.")
            .replace("+", "\\+")
            .replace("*", ".*")
            .replace("?", ".")

        return try {
            val compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
            compiledPattern.matcher(phoneNumber).matches()
        } catch (e: Exception) {
            false
        }
    }

    fun validatePattern(pattern: String): Boolean {
        if (pattern.isBlank()) return false
        return try {
            val regex = pattern
                .replace(".", "\\.")
                .replace("+", "\\+")
                .replace("*", ".*")
                .replace("?", ".")
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
            true
        } catch (e: Exception) {
            false
        }
    }
}