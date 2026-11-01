package com.phoneguard.util

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
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
 *    показать «импортировано N, пропущено M»;
 *  - правила разбора (разделители, заголовок, приоритет колонок, BOM) вынесены в
 *    [CsvImportParser]: здесь остались только ввод-вывод и нормализация номеров.
 */
@Singleton
class CsvImportHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phoneNumberNormalizer: PhoneNumberNormalizer
) {

    /**
     * @param numbers нормализованные E.164-номера без дубликатов;
     * @param totalLines число строк данных (пустые строки и комментарии не считаются,
     *   а строка-заголовок считается — она часть файла, но не считается пропуском);
     * @param skipped строки, которые не удалось разобрать как номер.
     */
    data class ImportResult(
        val numbers: List<String>,
        val totalLines: Int,
        val skipped: Int
    )

    suspend fun importBlockedNumbers(uri: Uri, isWhitelist: Boolean): List<String> =
        importNumbers(uri, isWhitelist).numbers

    /**
     * Читает файл и разбирает его [CsvImportParser].
     *
     * @param isWhitelist не влияет на разбор: и чёрный, и белый список хранят
     *   нормализованные номера одинаково, решение принимает вызывающий экран.
     *   Параметр сохранён ради стабильного API.
     */
    suspend fun importNumbers(uri: Uri, @Suppress("UNUSED_PARAMETER") isWhitelist: Boolean): ImportResult =
        withContext(Dispatchers.IO) {
            val outcome = runCatching {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        // lineSequence() читается лениво, поэтому разбор идёт внутри use {}.
                        CsvImportParser.parse(
                            lines = reader.lineSequence(),
                            normalizeStrict = { phoneNumberNormalizer.toValidE164(it) },
                            normalizeLoose = { phoneNumberNormalizer.toE164(it) }
                        )
                    }
                }
            }.getOrNull() ?: CsvImportParser.Outcome(emptyList(), 0, 0)

            ImportResult(
                numbers = outcome.numbers,
                totalLines = outcome.totalLines,
                skipped = outcome.skipped
            )
        }

}
