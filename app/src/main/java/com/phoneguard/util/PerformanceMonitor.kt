package com.phoneguard.util

import android.os.SystemClock
import android.util.Log
import com.phoneguard.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальный замер длительности операций.
 *
 * Раньше использовался Firebase Performance Monitoring (сетевой сервис). Теперь
 * замеры остаются в процессе: в debug они пишутся в logcat, в release —
 * отбрасываются. Совместимый API сохранён, чтобы не менять вызывающий код.
 */
@Singleton
class PerformanceMonitor @Inject constructor() {

    /** Замер одной операции. `null`-совместим с прежним Firebase `Trace?`. */
    inner class Trace internal constructor(private val name: String) {
        /**
         * Монотонные часы: `SystemClock.elapsedRealtime()` не зависит от перевода
         * системного времени (раньше длительность считалась по
         * `System.currentTimeMillis()` и могла получиться отрицательной).
         */
        private val timer = ElapsedTimer { SystemClock.elapsedRealtime() }

        fun start() {
            timer.start()
        }

        fun stop() {
            val duration = timer.stop() ?: return
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "trace=$name durationMs=$duration")
            }
        }

        fun incrementMetric(metricName: String, value: Long) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "trace=$name metric=$metricName value=$value")
            }
        }

        fun durationMs(): Long = timer.durationMs()
    }

    fun startTrace(traceName: String): Trace = Trace(traceName)

    companion object {
        private const val TAG = "PhoneGuardPerf"

        const val TRACE_SCAN = "phoneguard_full_scan"
        const val TRACE_VAULT_IMPORT = "phoneguard_vault_import"
        const val TRACE_FIREWALL_START = "phoneguard_firewall_start"
        const val TRACE_CALL_BLOCKER_LOAD = "phoneguard_call_blocker_load"
    }
}
