package com.phoneguard.threatintel

import java.io.Reader

/**
 * Построчное чтение публичного фида с жёсткими границами по памяти.
 *
 * Зачем отдельный класс: [ThreatIntelClient] читал фид OpenPhish через
 * `readUtf8Line()`, ограничивая только **число строк**. Ответ без переводов строк
 * (подменённый или «бесконечный» сервер) читался бы в память целиком — один
 * `readUtf8Line()` на гигабайтном ответе кладёт приложение по OOM. Здесь
 * одновременно ограничены и число строк, и число прочитанных символов, причём
 * буфер строки тоже не может вырасти сверх лимита.
 *
 * Логика без Android и OkHttp, поэтому проверяется JVM-тестами.
 */
internal object FeedLineReader {

    /**
     * @param lines прочитанные строки (без `\r` и `\n`)
     * @param truncated `true`, если чтение остановлено лимитом
     */
    data class Result(
        val lines: List<String>,
        val truncated: Boolean
    )

    /**
     * @param reader источник; закрывать его — забота вызывающего кода
     * @param maxLines максимальное число строк
     * @param maxChars максимальное число прочитанных символов
     */
    fun read(reader: Reader, maxLines: Int, maxChars: Int): Result {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxChars > 0) { "maxChars must be positive" }

        val lines = ArrayList<String>()
        val current = StringBuilder()
        var chars = 0

        while (true) {
            val code = reader.read()
            if (code < 0) break
            chars++
            if (chars > maxChars) {
                // Лимит по символам: обрываем и строку, и чтение.
                return Result(lines, truncated = true)
            }
            when (val ch = code.toChar()) {
                '\n' -> {
                    lines += current.toString()
                    current.setLength(0)
                    if (lines.size >= maxLines) return Result(lines, truncated = true)
                }
                '\r' -> Unit // CRLF: `\r` перед `\n` в строку не попадает
                else -> current.append(ch)
            }
        }

        if (current.isNotEmpty() && lines.size < maxLines) {
            lines += current.toString()
        }
        return Result(lines, truncated = false)
    }
}
