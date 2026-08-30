package com.phoneguard.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryOptimizationHelper @Inject constructor(
    private val context: android.content.Context
) {

    fun isBatteryLow(): Boolean {
        val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { it <= 15 } ?: false
    }

    fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }

    fun getAdaptiveScanInterval(): Long {
        return when {
            isBatteryLow() -> 12L * 60L * 60L * 1000L
            !isCharging() -> 6L * 60L * 60L * 1000L
            else -> 3L * 60L * 60L * 1000L
        }
    }

    fun getAdaptiveShoulderSurferInterval(): Long {
        val level = getBatteryLevel()
        return when {
            level <= 10 -> 10000L
            level <= 20 -> 6000L
            level <= 50 -> 4000L
            isCharging() -> 1500L
            else -> 2500L
        }
    }
}
