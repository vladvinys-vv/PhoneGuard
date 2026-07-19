package com.phoneguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.FirewallRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirewallDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FirewallDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.firewallDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertAndRetrieveRule() = runBlocking {
        val rule = FirewallRule(
            packageName = "com.test",
            appName = "Test",
            blockWifi = true,
            blockMobile = false,
            blockAll = false,
            blockVpn = false,
            blockBackground = false,
            blockedDomains = "[]",
            blockedIps = "[]",
            allowByDefault = true,
            allowWifiOnly = false,
            allowMobileOnly = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertRule(rule)

        val rules = dao.getAllRules().first()
        assertEquals(1, rules.size)
        assertEquals("com.test", rules.first().packageName)
    }

    @Test
    fun getRuleForPackageReturnsCorrectRule() = runBlocking {
        val rule = FirewallRule(
            packageName = "com.test",
            appName = "Test",
            blockWifi = true,
            blockMobile = false,
            blockAll = false,
            blockVpn = false,
            blockBackground = false,
            blockedDomains = "[]",
            blockedIps = "[]",
            allowByDefault = true,
            allowWifiOnly = false,
            allowMobileOnly = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertRule(rule)

        val found = dao.getRuleForPackage("com.test")
        assertNotNull(found)
        assertEquals("com.test", found!!.packageName)
    }

    @Test
    fun insertAndRetrieveLogs() = runBlocking {
        val log = FirewallLog(
            packageName = "com.test",
            appName = "Test",
            ipAddress = "1.2.3.4",
            connectionType = com.phoneguard.model.FirewallLog.ConnectionType.WIFI,
            trafficDirection = com.phoneguard.model.FirewallLog.TrafficDirection.OUTBOUND
        )
        dao.insertLog(log)

        val logs = dao.getAllLogs().first()
        assertEquals(1, logs.size)
    }

    @Test
    fun clearLogsEmptiesTable() = runBlocking {
        dao.insertLog(FirewallLog(packageName = "com.test", appName = "Test", connectionType = com.phoneguard.model.FirewallLog.ConnectionType.WIFI))
        dao.clearLogs()

        val logs = dao.getAllLogs().first()
        assertTrue(logs.isEmpty())
    }
}
