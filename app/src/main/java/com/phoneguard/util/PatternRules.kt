package com.phoneguard.util

import java.util.regex.Pattern

/**
 * Шаблоны номеров для чёрного/белого списка.
 *
 * Пользователь задаёт шаблон в «телефонном» виде: `*` — любая последовательность
 * символов, `?` — один символ, всё остальное — литерал. Раньше строка шаблона
 * экранировалась частично (только `.` и `+`), поэтому скобки, `|`, `[` и `$`
 * попадали в регулярное выражение как метасимволы: шаблон `+49 (170)` совпадал с
 * `49170`, а `+7|+49` — вообще с любым из двух префиксов. Теперь каждый символ
 * либо переводится в шаблон, либо экранируется через [Pattern.quote].
 */
object PatternRules {

    fun matchesPattern(phoneNumber: String, pattern: String): Boolean {
        val compiled = compile(pattern) ?: return false
        return compiled.matcher(phoneNumber).matches()
    }

    fun validatePattern(pattern: String): Boolean = compile(pattern) != null

    private fun compile(pattern: String): Pattern? {
        if (pattern.isBlank()) return null
        val regex = buildString {
            pattern.forEach { symbol ->
                when (symbol) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Pattern.quote(symbol.toString()))
                }
            }
        }
        return try {
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
        } catch (_: Exception) {
            null
        }
    }
}
