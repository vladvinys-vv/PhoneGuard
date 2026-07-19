package com.phoneguard.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationHelperTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `isBatteryLow returns false when battery is above 15`() {
        val batteryManager = mockk<BatteryManager>()
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns batteryManager
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 50

        val helper = BatteryOptimizationHelper(context)
        assertFalse(helper.isBatteryLow())
    }

    @Test
    fun `isBatteryLow returns true when battery is 15 or below`() {
        val batteryManager = mockk<BatteryManager>()
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns batteryManager
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 15

        val helper = BatteryOptimizationHelper(context)
        assertTrue(helper.isBatteryLow())
    }

    @Test
    fun `isCharging returns true when status is charging`() {
        val intent = mockk<Intent>()
        every { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } returns intent
        every { intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns BatteryManager.BATTERY_STATUS_CHARGING

        val helper = BatteryOptimizationHelper(context)
        assertTrue(helper.isCharging())
    }

    @Test
    fun `getBatteryLevel returns capacity`() {
        val batteryManager = mockk<BatteryManager>()
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns batteryManager
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80

        val helper = BatteryOptimizationHelper(context)
        assertEquals(80, helper.getBatteryLevel())
    }
}
