package com.phoneguard.util

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Импорт блокируемых номеров из CSV/текстового файла.
 *
 * Исправления:
 *  - номера нормализуются в E.164 через [PhoneNumberNormalizer] (libphonenumber),
 *    поэтому `0170 1234567` из Германии и `+49 170 1234567` дают одну запись;
 *    раньше строка отбрасывалась, если не состояла ровно из 7–15 цифр;
 *  - поддерживаются файлы с дополнительными колонками (`+491701234567,спам`),
 *    точка с запятой как разделитель и заголовок CSV;
 *  - дубликаты внутри файла удаляются;
 *  - результат содержит и принятые, и отклонённые строки, чтобы UI мог
 *    показать «импортировано N, пропущено M».
 */
@Singleton
class CsvImportHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phoneNumberNormalizer: PhoneNumberNormalizer
) {

    data class ImportResult(
        val numbers: List<String>,
        val totalLines: Int,
        val skipped: Int
    )

    suspend fun importBlockedNumbers(uri: Uri, isWhitelist: Boolean): List<String> =
        importNumbers(uri, isWhitelist).numbers

    suspend fun importNumbers(uri: Uri, @Suppress("UNUSED_PARAMETER") isWhitelist: Boolean): ImportResult =
        withContext(Dispatchers.IO) {
            val accepted = LinkedHashSet<String>()
            var total = 0
            var skipped = 0

            runCatching {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.lineSequence().forEach { rawLine ->
                            val line = rawLine.trim()
                            if (line.isEmpty() || line.startsWith("#")) return@forEach
                            total++

                            val candidates = extractCandidates(line)
                            // Сначала строгая проверка по metadata libphonenumber,
                            // затем — мягкая нормализация (номера необычного формата).
                            val normalized =
                                candidates.firstNotNullOfOrNull { phoneNumberNormalizer.toValidE164(it) }
                                    ?: candidates.firstNotNullOfOrNull { phoneNumberNormalizer.toE164(it) }

                            if (normalized != null) {
                                accepted += normalized
                            } else if (total > 1 || !looksLikeHeader(line)) {
                                skipped++
                            }
                        }
                    }
                }
            }

            ImportResult(numbers = accepted.toList(), totalLines = total, skipped = skipped)
        }

    /**
     * Возвращает потенциальные номера из строки: значение первой колонки и,
     * если там нет цифр, любое поле похожее на номер.
     */
    private fun extractCandidates(line: String): List<String> {
        val fields = line.split(';', ',', '\t', '|').map { it.trim().trim('"') }
        val withDigits = fields.filter { field -> field.any { it.isDigit() } }
        return withDigits.ifEmpty { listOf(line) }
    }

    private fun looksLikeHeader(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return HEADER_HINTS.any { hint -> lower.contains(hint) }
    }

    private companion object {
        val HEADER_HINTS = listOf("phone", "number", "номер", "telefon", "nummer")
    }
}
