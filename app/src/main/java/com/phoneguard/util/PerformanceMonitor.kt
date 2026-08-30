package com.phoneguard.util

import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.HttpMetric
import com.google.firebase.perf.metrics.Trace
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerformanceMonitor @Inject constructor() {

    fun startTrace(traceName: String): Trace {
        return FirebasePerformance.getInstance().newTrace(traceName)
    }

    fun startHttpMetric(url: String, method: String): HttpMetric {
        return FirebasePerformance.getInstance().newHttpMetric(
            url,
            com.google.firebase.perf.metrics.HttpMethod.valueOf(method.uppercase())
        )
    }

    companion object {
        const val TRACE_SCAN = "phoneguard_full_scan"
        const val TRACE_VAULT_IMPORT = "phoneguard_vault_import"
        const val TRACE_FIREWALL_START = "phoneguard_firewall_start"
        const val TRACE_CALL_BLOCKER_LOAD = "phoneguard_call_blocker_load"
    }
}