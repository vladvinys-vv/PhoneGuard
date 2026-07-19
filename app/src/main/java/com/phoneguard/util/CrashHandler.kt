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
    private val crashlytics = FirebaseCrashlytics.getInstance()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashlytics.recordException(throwable)
            crashlytics.log("Uncaught exception in thread: ${thread.name}")
            Thread.currentThread().also {
                it.uncaughtExceptionHandler?.uncaughtException(it, throwable)
            }
        }
    }

    fun log(message: String) {
        crashlytics.log(message)
    }

    fun recordException(throwable: Throwable) {
        crashlytics.recordException(throwable)
    }

    fun setUserId(userId: String) {
        crashlytics.setUserId(userId)
    }

    companion object {
        val coroutineExceptionHandler = CoroutineExceptionHandler { _, exception ->
            FirebaseCrashlytics.getInstance().recordException(exception)
        }
    }
}