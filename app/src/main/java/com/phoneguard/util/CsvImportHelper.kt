package com.phoneguard.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CsvImportHelper @Inject constructor(
    private val context: Context
) {
    suspend fun importBlockedNumbers(uri: Uri, isWhitelist: Boolean): List<String> = withContext(Dispatchers.IO) {
        val numbers = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val number = line?.trim()
                        if (!number.isNullOrEmpty() && number.matches(Regex("^[+]?[0-9]{7,15}$"))) {
                            numbers.add(number)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Return partial results if any
        }
        numbers
    }
}