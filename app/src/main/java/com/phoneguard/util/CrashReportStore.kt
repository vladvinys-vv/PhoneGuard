package com.phoneguard.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файловый слой отчётов о падениях.
 *
 * Отделён от [CrashHandler] намеренно: у обработчика — установка перехватчика и системные
 * вызовы, а здесь только `java.io`. Благодаря этому правила «сколько отчётов хранить» и
 * «когда обнулять журнал сессии» проверяются обычными JVM-тестами на настоящей файловой
 * системе, а не «на глаз» на телефоне.
 *
 * Правила:
 *  1. Хранится не больше [MAX_REPORTS] отчётов: иначе падающее в цикле приложение заполняет
 *     внутреннее хранилище, и телефон перестаёт работать не из-за бага, а из-за журналов.
 *  2. Имя отчёта содержит миллисекунды ([fileName]): раньше хватало секунд, и два падения
 *     подряд в одну секунду затирали друг друга — оставался только последний след.
 *  3. Журнал сессии ([SESSION_LOG]) обнуляется по достижении [MAX_SESSION_LOG_BYTES], а не
 *     растёт бесконечно: он пишется на каждое сообщение [CrashHandler.log].
 */
internal class CrashReportStore(private val directory: File) {

    /** Каталог отчётов; создаётся при первом обращении. */
    fun ensureDirectory(): File = directory.apply { mkdirs() }

    /** Все файлы каталога, свежие сверху (включая журнал сессии). */
    fun reports(): List<File> =
        ensureDirectory().listFiles { file -> file.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /**
     * Только отчёты о падениях, свежие сверху.
     *
     * Сортировка **по имени**, а не по `lastModified()`: имя содержит время падения с
     * точностью до миллисекунд, а метка файла — нет. На быстрой файловой системе серия
     * падений получает одинаковый `lastModified`, порядок становится произвольным, и
     * подрезка удаляла случайные отчёты — в том числе самые свежие, которые и нужны.
     */
    fun crashReports(): List<File> =
        reports().filter { it.name.startsWith(CRASH_PREFIX) }.sortedByDescending { it.name }

    /**
     * Пишет отчёт о падении и подрезает каталог до [MAX_REPORTS].
     *
     * @return созданный файл или `null`, если записать не удалось (падать из обработчика
     * падений нельзя — это рекурсия).
     */
    fun writeReport(throwable: Throwable, now: Long = System.currentTimeMillis()): File? = try {
        val file = File(ensureDirectory(), fileName(now))
        file.writeText(formatReport(throwable, now))
        prune()
        file
    } catch (_: Exception) {
        null
    }

    /** Дописывает строку в журнал сессии, обнуляя его при превышении лимита. */
    fun appendSessionLine(message: String, now: Long = System.currentTimeMillis()): Boolean = try {
        val file = File(ensureDirectory(), SESSION_LOG)
        if (file.length() > MAX_SESSION_LOG_BYTES) file.delete()
        file.appendText("$now $message\n")
        true
    } catch (_: Exception) {
        false
    }

    /** Удаляет все файлы каталога (журнал сессии тоже). */
    fun clear() {
        reports().forEach { file -> runCatching { file.delete() } }
    }

    /** Оставляет [MAX_REPORTS] самых свежих отчётов. */
    fun prune() {
        val crashes = crashReports()
        if (crashes.size <= MAX_REPORTS) return
        crashes.drop(MAX_REPORTS).forEach { file -> runCatching { file.delete() } }
    }

    companion object {
        /** Префикс отчёта о падении; по нему же считается лимит. */
        const val CRASH_PREFIX = "crash_"

        /** Журнал сессии: строки `log()` между падениями. */
        const val SESSION_LOG = "session.log"

        /** Сколько отчётов хранится на устройстве. */
        const val MAX_REPORTS = 20

        /** Лимит журнала сессии (256 КБ); при превышении он начинается заново. */
        const val MAX_SESSION_LOG_BYTES = 256 * 1024

        /** Имя отчёта: секунды и миллисекунды, чтобы падения подряд не затирались. */
        fun fileName(now: Long): String =
            CRASH_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now)) + ".txt"

        /**
         * Текст отчёта.
         *
         * Чистая функция (кроме формата даты): её проверяет тест, и она же попадает в файл.
         */
        fun formatReport(throwable: Throwable, timestamp: Long = System.currentTimeMillis()): String {
            val stackTrace = StringWriter().also { writer ->
                PrintWriter(writer).use { throwable.printStackTrace(it) }
            }.toString()

            val header = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
            return buildString {
                appendLine("PhoneGuard crash report")
                appendLine("time: $header")
                appendLine("type: ${throwable.javaClass.name}")
                appendLine("message: ${throwable.message}")
                appendLine("---")
                append(stackTrace)
            }
        }
    }
}
