package com.phoneguard.util

import android.content.Context
import android.util.Log
import com.phoneguard.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Перехват необработанных исключений с сохранением отчёта **на устройстве**.
 *
 * Раньше отчёт отправлялся в Firebase Crashlytics. Для приложения, которое
 * заявляет «Zero telemetry» и работу без облака, это недопустимо: стек-трейсы
 * могут содержать пути, номера и названия пакетов. Теперь отчёт пишется в
 * `filesDir/crash-reports/` (не более [MAX_REPORTS] файлов) и остаётся на телефоне.
 *
 * Файлы можно посмотреть в приложении или забрать через `adb`, подключившись к
 * собственному устройству.
 */
@Singleton
class CrashHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun install() {
        if (previousHandler != null) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordException(throwable)
            log("Uncaught exception in thread: ${thread.name}")
            // Обязательно передаём управление дальше, иначе система не покажет
            // диалог «Приложение остановлено» и процесс останется в неопределённом состоянии.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
        appendToSessionLog(message)
    }

    fun recordException(throwable: Throwable) {
        if (BuildConfig.DEBUG) Log.e(TAG, "recordException", throwable)
        writeCrashReport(throwable)
    }

    /**
     * Раньше устанавливал userId в Crashlytics. Оставлен как no-op ради
     * совместимости: приложение не собирает идентификаторы пользователей.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setUserId(userId: String) {
        // Намеренно ничего не делает: никакой идентификации пользователя.
    }

    /** Каталог с локальными отчётами (для показа в настройках). */
    fun crashReportsDir(): File = File(context.filesDir, CRASH_DIR).apply { mkdirs() }

    fun listReports(): List<File> =
        crashReportsDir().listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    fun clearReports() {
        listReports().forEach { it.delete() }
    }

    private fun writeCrashReport(throwable: Throwable) {
        try {
            val dir = crashReportsDir()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_$timestamp.txt")
            file.writeText(formatReport(throwable))
            pruneOldReports(dir)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to persist crash report", e)
        }
    }

    private fun appendToSessionLog(message: String) {
        try {
            val file = File(crashReportsDir(), "session.log")
            if (file.length() > MAX_SESSION_LOG_BYTES) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Exception) {
            // Логирование не должно ломать приложение.
        }
    }

    private fun pruneOldReports(dir: File) {
        val reports = dir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        reports.drop(MAX_REPORTS).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "PhoneGuardCrash"
        private const val CRASH_DIR = "crash-reports"
        private const val MAX_REPORTS = 20
        private const val MAX_SESSION_LOG_BYTES = 256 * 1024

        /** Чистая функция форматирования — покрыта юнит-тестом. */
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
