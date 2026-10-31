package com.phoneguard.intrusion

import android.content.Context
import android.provider.Settings
import android.os.Build
import java.io.File

class IntrusionDetector {
    fun detect(signals: List<IntrusionSignal>): List<IntrusionEvent> =
        signals.map { signal ->
            IntrusionEvent(
                type = signal.type,
                severity = signal.severity,
                evidence = signal.evidence
            )
        }

    fun collectEnvironmentSignals(context: Context): List<IntrusionSignal> {
        val signals = mutableListOf<IntrusionSignal>()
        if (Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)
        ) {
            signals += IntrusionSignal(
                type = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
                evidence = mapOf("reason" to "emulator_or_analysis_environment"),
                severity = IntrusionSeverity.MEDIUM
            )
        }
        if (File("/system/bin/su").exists() || File("/system/xbin/su").exists()) {
            signals += IntrusionSignal(
                type = IntrusionType.RAT_DETECTED,
                evidence = mapOf("reason" to "root_binary_present"),
                severity = IntrusionSeverity.HIGH
            )
        }
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            signals += IntrusionSignal(
                type = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
                evidence = mapOf("reason" to "debuggable_build"),
                severity = IntrusionSeverity.LOW
            )
        }
        val suspiciousPackages = listOf("frida", "xposed", "magisk", "substrate", "cydia")
        try {
            context.packageManager.getInstalledApplications(0).forEach { app ->
                if (suspiciousPackages.any { app.packageName.contains(it, ignoreCase = true) }) {
                    signals += IntrusionSignal(
                        type = IntrusionType.HOOKING_DETECTED,
                        evidence = mapOf("package" to app.packageName),
                        severity = IntrusionSeverity.HIGH
                    )
                }
            }
        } catch (_: Exception) {}
        try {
            val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            if (adbEnabled) {
                signals += IntrusionSignal(
                    type = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
                    evidence = mapOf("reason" to "adb_enabled"),
                    severity = IntrusionSeverity.MEDIUM
                )
            }
        } catch (_: Exception) {}
        try {
            val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (enabledServices.isNotBlank() && !enabledServices.contains(context.packageName)) {
                signals += IntrusionSignal(
                    type = IntrusionType.RAT_DETECTED,
                    evidence = mapOf("reason" to "external_accessibility_service", "services" to enabledServices),
                    severity = IntrusionSeverity.MEDIUM
                )
            }
        } catch (_: Exception) {}
        return signals
    }
}
