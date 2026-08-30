package com.phoneguard.util

import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashHandlerTest {

    @Test
    fun `install sets default uncaught exception handler`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)

        every { FirebaseCrashlytics.getInstance() } returns crashlytics

        val handler = CrashHandler(context)
        handler.install()

        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(currentHandler != null)
    }

    @Test
    fun `log sends message to crashlytics`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)

        every { FirebaseCrashlytics.getInstance() } returns crashlytics

        val handler = CrashHandler(context)
        handler.log("Test message")

        verify { crashlytics.log("Test message") }
    }

    @Test
    fun `recordException sends exception to crashlytics`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)
        val exception = RuntimeException("Test exception")

        every { FirebaseCrashlytics.getInstance() } returns crashlytics

        val handler = CrashHandler(context)
        handler.recordException(exception)

        verify { crashlytics.recordException(exception) }
    }

    @Test
    fun `setUserId sets user id in crashlytics`() {
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)

        every { FirebaseCrashlytics.getInstance() } returns crashlytics

        val handler = CrashHandler(context)
        handler.setUserId("user123")

        verify { crashlytics.setUserId("user123") }
    }
}
