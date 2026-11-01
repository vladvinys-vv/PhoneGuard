package com.phoneguard.intrusion

import com.phoneguard.data.local.IntrusionEventEntity

/*
 * Формат улик в базе — `ключ=значение;ключ=значение`. Разделители экранируются обратной
 * косой чертой, иначе значение с `;` или `=` разваливало запись: `path=/sdcard/a;b.jpg`
 * читалось как две улики, а `rule=a=b` теряло часть значения. Строки, записанные до
 * появления экранирования, не содержат `\` и разбираются как раньше — миграция не нужна.
 */

private const val PAIRS_SEPARATOR = ';'
private const val KEY_VALUE_SEPARATOR = '='
private const val ESCAPE_CHAR = '\\'

fun IntrusionEventEntity.toIntrusionEvent(): IntrusionEvent =
    IntrusionEvent(
        id = id,
        timestamp = timestamp,
        type = type,
        severity = severity,
        evidence = parseEvidence(evidence),
        uid = uid,
        packageName = packageName,
        ipAddress = ipAddress,
        photoPath = photoPath,
        handled = handled
    )

fun IntrusionEvent.toEntity(): IntrusionEventEntity =
    IntrusionEventEntity(
        id = id,
        timestamp = timestamp,
        type = type,
        severity = severity,
        evidence = evidence.entries.joinToString(PAIRS_SEPARATOR.toString()) {
            "${escapeEvidence(it.key)}$KEY_VALUE_SEPARATOR${escapeEvidence(it.value)}"
        },
        uid = uid,
        packageName = packageName,
        ipAddress = ipAddress,
        photoPath = photoPath,
        handled = handled
    )

/** Разбирает `k=v;k=v` с учётом экранирования. */
internal fun parseEvidence(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    splitUnescaped(raw).forEach { pair ->
        if (pair.isBlank()) return@forEach
        val separator = indexOfUnescaped(pair, KEY_VALUE_SEPARATOR)
        if (separator < 0) {
            result[unescapeEvidence(pair)] = ""
        } else {
            result[unescapeEvidence(pair.substring(0, separator))] =
                unescapeEvidence(pair.substring(separator + 1))
        }
    }
    return result
}

internal fun escapeEvidence(text: String): String = buildString {
    text.forEach { char ->
        if (char == ESCAPE_CHAR || char == PAIRS_SEPARATOR || char == KEY_VALUE_SEPARATOR) append(ESCAPE_CHAR)
        append(char)
    }
}

internal fun unescapeEvidence(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val next = text.getOrNull(index + 1)
        if (char == ESCAPE_CHAR && next != null &&
            (next == ESCAPE_CHAR || next == PAIRS_SEPARATOR || next == KEY_VALUE_SEPARATOR)
        ) {
            append(next)
            index += 2
        } else {
            append(char)
            index++
        }
    }
}

private fun splitUnescaped(raw: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == ESCAPE_CHAR && index + 1 < raw.length) {
            current.append(char).append(raw[index + 1])
            index += 2
            continue
        }
        if (char == PAIRS_SEPARATOR) {
            parts.add(current.toString())
            current.setLength(0)
        } else {
            current.append(char)
        }
        index++
    }
    parts.add(current.toString())
    return parts
}

private fun indexOfUnescaped(text: String, target: Char): Int {
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == ESCAPE_CHAR) {
            index += 2
            continue
        }
        if (char == target) return index
        index++
    }
    return -1
}
