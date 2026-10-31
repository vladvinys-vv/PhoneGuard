package com.phoneguard.util

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val crashlytics: FirebaseCrashlytics? by lazy {
        runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()
    }
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun install() {
        if (previousHandler != null) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashlytics?.recordException(throwable)
            crashlytics?.log("Uncaught exception in thread: ${thread.name}")
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(message: String) {
        crashlytics?.log(message)
    }

    fun recordException(throwable: Throwable) {
        crashlytics?.recordException(throwable)
    }

    fun setUserId(userId: String) {
        crashlytics?.setUserId(userId)
    }

    companion object {
        val coroutineExceptionHandler = CoroutineExceptionHandler { _, exception ->
            runCatching { FirebaseCrashlytics.getInstance().recordException(exception) }
        }
    }
}