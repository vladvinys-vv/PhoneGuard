package com.phoneguard.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Отчёты о падениях теперь пишутся локально (Firebase Crashlytics удалён ради
 * соблюдения обещания «Zero telemetry»), поэтому тесты проверяют файловый слой
 * и форматирование, а не вызовы внешнего SDK.
 */
class CrashHandlerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    @After
    fun tearDown() {
        installedHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
    }

    private fun handler(fileDir: File, context: Context = mockk(relaxed = true)): CrashHandler {
        every { context.filesDir } returns fileDir
        return CrashHandler(context)
    }

    @Test
    fun `install chains to previous handler and writes a report`() {
        // Детерминированный «предыдущий» обработчик: так тест не зависит от того,
        // что успел установить JUnit/Gradle runner.
        var delegatedThread: Thread? = null
        var delegatedError: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { thread, error ->
            delegatedThread = thread
            delegatedError = error
        }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        installedHandler = previous

        val crashHandler = handler(temporaryFolder.newFolder("files-install"))
        crashHandler.install()

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(installed)
        assertTrue(installed !== previous)

        // Повторная установка не должна заворачивать обработчик в себя второй раз.
        crashHandler.install()
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())

        val current = Thread.currentThread()
        val error = RuntimeException("boom-install")
        installed!!.uncaughtException(current, error)

        // Управление передано предыдущему обработчику...
        assertSame(current, delegatedThread)
        assertSame(error, delegatedError)
        // ...и отчёт сохранён локально.
        assertTrue(crashHandler.listReports().any { it.name.startsWith("crash_") })
    }

    @Test
    fun `recordException writes a local crash report`() {
        val filesDir = temporaryFolder.newFolder("files")
        val handler = handler(filesDir)

        handler.recordException(RuntimeException("Test exception"))

        val reports = handler.listReports().filter { it.name.startsWith("crash_") }
        assertEquals(1, reports.size)
        val content = reports.first().readText()
        assertTrue(content.contains("RuntimeException"))
        assertTrue(content.contains("Test exception"))
    }

    @Test
    fun `log appends to the session log without crashing`() {
        val filesDir = temporaryFolder.newFolder("files")
        val handler = handler(filesDir)

        handler.log("Test message")

        val sessionLog = File(filesDir, "crash-reports/session.log")
        assertTrue(sessionLog.exists())
        assertTrue(sessionLog.readText().contains("Test message"))
    }

    @Test
    fun `setUserId is a no-op and stores nothing`() {
        val filesDir = temporaryFolder.newFolder("files")
        val handler = handler(filesDir)

        handler.setUserId("user123")

        assertTrue(handler.listReports().none { it.name.contains("user123") })
    }

    @Test
    fun `formatReport contains header and stack trace`() {
        val report = CrashHandler.formatReport(IllegalStateException("boom"), timestamp = 0L)

        assertTrue(report.startsWith("PhoneGuard crash report"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("boom"))
    }
}
