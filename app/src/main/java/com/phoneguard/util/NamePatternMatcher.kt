package com.phoneguard.util

import java.util.Locale

/**
 * Сравнение имён пакетов/ярлыков с «подозрительными» шаблонами.
 *
 * Раньше каждая проверка (`DeviceAdminRiskPolicy`, `AccessibilityHeuristics`,
 * `OverlayAttackCheck`) делала это по-своему и почти всегда через `contains`. Отсюда две
 * ошибки сразу, и обе видны пользователю:
 *
 *  * **пропуск.** Шаблон `anti.theft` не находил ни ярлык `ANTI-THEFT`, ни пакет
 *    `com.x.antitheft`: приложение-слежка, назвавшееся «анти-воровством», попадало в отчёт
 *    как обычный сторонний администратор. Так же `auto.click` не находил `AutoClicker`;
 *  * **ложная тревога.** Короткие общие слова искались подстрокой: `admin` находился в
 *    `badminton`, `lock` — в `blockchain` и `clocks`, `eng` — в `avengers`. В отчёте о
 *    безопасности это дороже, чем кажется: одна ложная находка обесценивает остальные.
 *
 * Правило здесь одно для всех:
 *  1. имя режется на слова по разделителям (`.`, `-`, `_`, пробел) и по camelCase;
 *  2. шаблон тоже режется на слова и склеивается (`anti.theft` → `antitheft`);
 *  3. совпадение засчитывается, если склейка шаблона стоит **в начале слова** (или на стыке
 *     слов) — так находятся `adminservice`, `KeyLog Pro`, `App Lock`;
 *  4. иначе — только «внутри слова» и только для шаблонов от [EMBEDDED_MIN_LENGTH] символов,
 *     чтобы не терять `keylogger` и `antithefttool`, но не ловить `badminton`.
 */
internal object NamePatternMatcher {

    /**
     * С какой длины шаблон можно искать внутри слова.
     *
     * Короче — только с начала слова: `lock` в `blockchain` совпадением не считается,
     * а `keylog` в `keylogger` — считается.
     */
    const val EMBEDDED_MIN_LENGTH = 6

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Слова имени в нижнем регистре.
     *
     * `Locale.ROOT`, а не `getDefault()`: в турецкой локали `I` превращается в `ı`, и проверка
     * начинала зависеть от языка системы.
     */
    fun tokens(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val spaced = StringBuilder(value.length + 8)
        for (index in value.indices) {
            val ch = value[index]
            val previous = if (index > 0) value[index - 1] else null
            // Граница camelCase: «mSpy» → «m Spy», «AntiTheft» → «Anti Theft».
            val camelBoundary = previous != null &&
                ch.isUpperCase() &&
                (previous.isLowerCase() || previous.isDigit())
            // Граница «буква/цифра»: «screen2filter» → «screen 2 filter». Цифры в названиях
            // пишут вставкой в любое место, и они не должны разрывать шаблон.
            val digitBoundary = previous != null &&
                previous.isLetterOrDigit() &&
                ch.isLetterOrDigit() &&
                ch.isDigit() != previous.isDigit()
            if (camelBoundary || digitBoundary) spaced.append(' ')
            spaced.append(ch)
        }
        return spaced.toString()
            .lowercase(Locale.ROOT)
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }
    }

    /** Совпадает ли [value] с [pattern] по правилам из описания объекта. */
    fun matches(value: String, pattern: String): Boolean {
        val needle = tokens(pattern).joinToString(separator = "")
        if (needle.isEmpty()) return false
        val words = tokens(value)
        if (words.isEmpty()) return false

        for (start in words.indices) {
            val tail = buildString {
                for (index in start until words.size) {
                    // Чисто цифровые слова при склейке пропускаем: «spy2go» и «screen2filter»
                    // должны совпадать так же, как «spygo» и «screenfilter».
                    if (words[index].all { it.isDigit() }) continue
                    append(words[index])
                }
            }
            if (tail.startsWith(needle)) return true
            if (needle.length >= EMBEDDED_MIN_LENGTH && tail.contains(needle)) return true
        }
        return false
    }

    /**
     * Первый совпавший шаблон из [patterns] (в порядке списка) или `null`.
     *
     * @param appLabel ярлык приложения; `null` — приложения больше нет (проверяется только пакет)
     */
    fun firstMatch(packageName: String, appLabel: String?, patterns: List<String>): String? =
        patterns.firstOrNull { pattern ->
            matches(packageName, pattern) || (appLabel != null && matches(appLabel, pattern))
        }
}
