package com.phoneguard.util

import android.content.Context
import android.util.Log
import com.phoneguard.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Перехват необработанных исключений с сохранением отчёта **на устройстве**.
 *
 * Раньше отчёт отправлялся в Firebase Crashlytics. Для приложения, которое
 * заявляет «Zero telemetry» и работу без облака, это недопустимо: стек-трейсы
 * могут содержать пути, номера и названия пакетов. Теперь отчёт пишется в
 * `filesDir/crash-reports/` (не более [CrashReportStore.MAX_REPORTS] файлов) и остаётся на телефоне.
 *
 * Файлы можно посмотреть в приложении или забрать через `adb`, подключившись к
 * собственному устройству.
 *
 * Файловая часть — в [CrashReportStore]: сколько отчётов хранить и когда обнулять журнал
 * сессии, проверяется JVM-тестами на настоящей файловой системе.
 */
@Singleton
class CrashHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val store: CrashReportStore by lazy { CrashReportStore(crashReportsDir()) }

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

    fun listReports(): List<File> = store.reports()

    fun clearReports() {
        store.clear()
    }

    private fun writeCrashReport(throwable: Throwable) {
        if (store.writeReport(throwable) == null) {
            Log.w(TAG, "Unable to persist crash report")
        }
    }

    private fun appendToSessionLog(message: String) {
        // Логирование не должно ломать приложение: отказ записи игнорируется осознанно.
        store.appendSessionLine(message)
    }

    companion object {
        private const val TAG = "PhoneGuardCrash"

        /** Тот же каталог, что и у [CrashReportStore]; имя нужно только здесь. */
        private const val CRASH_DIR = "crash-reports"

        /** Форматирование живёт в [CrashReportStore] — там же его тест. */
        fun formatReport(throwable: Throwable, timestamp: Long = System.currentTimeMillis()): String =
            CrashReportStore.formatReport(throwable, timestamp)
    }
}
