package com.phoneguard.util

import java.util.Locale

/**
 * Чистая логика разбора CSV/текстового файла со номерами (без Android-зависимостей).
 *
 * Вынесено из [CsvImportHelper], чтобы правила «что считать номером, что заголовком,
 * а что пропущенной строкой» можно было проверить JVM-тестами: сам хелпер тянет
 * `Context`/`Uri` и в офлайн-тестах недоступен.
 *
 * Правила, которые закреплены тестами:
 *  - пустые строки и комментарии (`#`) не считаются ни данными, ни пропуском;
 *  - BOM (`\uFEFF`) в начале файла отбрасывается — его оставляют Excel и часть
 *    банковских выгрузок, и без этого первая строка «не узнавалась» как заголовок;
 *  - разделители `;`, `,` (табуляция) и `|` равноправны, кавычки вокруг поля снимаются;
 *  - приоритет отдаётся «написанному» номеру (`+49 170 1234567`, `0170/123-45`), а не
 *    голой цифровой колонке: в выгрузках встречается `1700000000;0170 1234567`, где
 *    первая колонка — id/таймстамп, и раньше именно она попадала в чёрный список;
 *  - первая строка, которая не дала номера и похожа на заголовок (`phone`, `nummer`,
 *    `номер`, …), не увеличивает счётчик пропущенных — она не данные;
 *  - дубликаты внутри файла схлопываются в порядке появления.
 */
internal object CsvImportParser {

    /** Итог разбора: принятые номера, число строк данных и число пропущенных. */
    data class Outcome(
        val numbers: List<String>,
        val totalLines: Int,
        val skipped: Int
    )

    private val SEPARATORS = charArrayOf(';', ',', '\t', '|')

    /** Подсказки заголовка на языках, для которых есть локали приложения. */
    private val HEADER_HINTS = listOf("phone", "number", "номер", "telefon", "nummer")

    private const val BOM = '\uFEFF'

    /** Убирает BOM, который Excel ставит в начало UTF-8 файла. */
    fun stripBom(line: String): String = line.trimStart(BOM)

    /** Строка не содержит данных: пустая или комментарий. */
    fun isIgnorable(line: String): Boolean {
        val trimmed = stripBom(line).trim()
        return trimmed.isEmpty() || trimmed.startsWith("#")
    }

    /**
     * Кандидаты на номер из одной строки, в порядке приоритета.
     *
     * Поля без единой цифры отбрасываются (`Muster, Hans` → не кандидат). Если цифр нет
     * ни в одном поле, возвращается вся строка целиком — нормализатор сам решит, номер
     * это или мусор.
     */
    fun candidates(line: String): List<String> {
        val fields = stripBom(line).split(*SEPARATORS).map { it.trim().trim('"') }
        val withDigits = fields.filter { field -> field.any { it.isDigit() } }
        val raw = withDigits.ifEmpty { listOf(stripBom(line).trim()) }
        // sortedByDescending стабильна: при равном приоритете порядок колонок сохраняется.
        return raw.sortedByDescending { priority(it) }
    }

    /**
     * Насколько поле похоже на записанный номер: `+` (международный формат) важнее
     * разделителей внутри номера, а голая последовательность цифр — самый слабый вариант.
     */
    private fun priority(candidate: String): Int = when {
        '+' in candidate -> 3
        candidate.any { it in " -/()" } -> 2
        else -> 1
    }

    /** Похожа ли строка на заголовок таблицы, а не на данные. */
    fun looksLikeHeader(line: String): Boolean {
        val lower = stripBom(line).lowercase(Locale.ROOT)
        return HEADER_HINTS.any { hint -> lower.contains(hint) }
    }

    /**
     * Разбирает строки файла.
     *
     * @param normalizeStrict строгая нормализация (libphonenumber metadata);
     * @param normalizeLoose мягкая нормализация для номеров необычного формата.
     */
    fun parse(
        lines: Sequence<String>,
        normalizeStrict: (String) -> String?,
        normalizeLoose: (String) -> String?
    ): Outcome {
        val accepted = LinkedHashSet<String>()
        var total = 0
        var skipped = 0

        for (rawLine in lines) {
            if (isIgnorable(rawLine)) continue
            total++
            val candidates = candidates(rawLine)
            val normalized = candidates.firstNotNullOfOrNull(normalizeStrict)
                ?: candidates.firstNotNullOfOrNull(normalizeLoose)

            when {
                normalized != null -> accepted += normalized
                // Заголовок может быть только первой строкой данных.
                total == 1 && looksLikeHeader(rawLine) -> Unit
                else -> skipped++
            }
        }

        return Outcome(numbers = accepted.toList(), totalLines = total, skipped = skipped)
    }
}
