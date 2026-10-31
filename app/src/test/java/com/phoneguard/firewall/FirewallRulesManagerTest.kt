package com.phoneguard.firewall

import com.phoneguard.data.local.FirewallDao
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Матрица правил фаервола: какое сочетание условий реально означает «весь трафик
 * приложения заблокирован». От этого зависит, попадёт пакет в TUN или пойдёт мимо.
 */
class FirewallRulesManagerTest {

    private fun packageRule(
        packageName: String = "com.example.app",
        blockWifi: Boolean = false,
        blockMobile: Boolean = false,
        blockAll: Boolean = false,
        allowByDefault: Boolean = true,
        allowWifiOnly: Boolean = false,
        allowMobileOnly: Boolean = false
    ) = FirewallRule(
        packageName = packageName,
        appName = packageName,
        blockWifi = blockWifi,
        blockMobile = blockMobile,
        blockAll = blockAll,
        allowByDefault = allowByDefault,
        allowWifiOnly = allowWifiOnly,
        allowMobileOnly = allowMobileOnly
    )

    private fun managerWith(vararg rules: FirewallRule): FirewallRulesManager {
        val dao = mockk<FirewallDao>(relaxed = true)
        every { dao.getAllRules() } returns flowOf(rules.toList())
        val manager = FirewallRulesManager(FirewallRepository(dao))
        runBlocking { manager.loadRules() }
        return manager
    }

    @Test
    fun defaultRuleAllowsTrafficOnBothNetworks() {
        val manager = managerWith(packageRule())
        val rule = manager.getRuleForPackage("com.example.app")!!
        assertFalse(manager.shouldBlockAllTraffic(rule, isOnWifi = true))
        assertFalse(manager.shouldBlockAllTraffic(rule, isOnWifi = false))
    }

    @Test
    fun blockAllBeatsNetworkType() {
        val manager = managerWith(packageRule(blockAll = true))
        val rule = manager.getRuleForPackage("com.example.app")!!
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = true))
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = false))
    }

    @Test
    fun blockWifiOnlyAffectsWifi() {
        val manager = managerWith(packageRule(blockWifi = true))
        val rule = manager.getRuleForPackage("com.example.app")!!
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = true))
        assertFalse(manager.shouldBlockAllTraffic(rule, isOnWifi = false))
    }

    @Test
    fun blockMobileOnlyAffectsMobile() {
        val manager = managerWith(packageRule(blockMobile = true))
        val rule = manager.getRuleForPackage("com.example.app")!!
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = false))
        assertFalse(manager.shouldBlockAllTraffic(rule, isOnWifi = true))
    }

    @Test
    fun wifiOnlyRuleBlocksMobileTraffic() {
        val manager = managerWith(packageRule(allowWifiOnly = true))
        val rule = manager.getRuleForPackage("com.example.app")!!
        assertFalse(manager.shouldBlockAllTraffic(rule, isOnWifi = true))
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = false))
    }

    @Test
    fun mobileOnlyRuleBlocksWifiTraffic() {
        val manager = managerWith(packageRule(allowMobileOnly = true))
        val rule = manager.getRuleForPackage("com.example.app")!!
        assertFalse(manager.shouldBlockAllTraffic(rule, isOnWifi = false))
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = true))
    }

    @Test
    fun denyByDefaultBlocksEverything() {
        val manager = managerWith(packageRule(allowByDefault = false))
        val rule = manager.getRuleForPackage("com.example.app")!!
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = true))
        assertTrue(manager.shouldBlockAllTraffic(rule, isOnWifi = false))
    }

    @Test
    fun blockedPackagesReturnsOnlyFullyBlockedApps() {
        val manager = managerWith(
            packageRule(packageName = "com.blocked.all", blockAll = true),
            packageRule(packageName = "com.blocked.mobile", blockMobile = true),
            packageRule(packageName = "com.allowed")
        )

        assertEquals(setOf("com.blocked.all", "com.blocked.mobile"), manager.blockedPackages(isOnWifi = false))
        assertEquals(setOf("com.blocked.all"), manager.blockedPackages(isOnWifi = true))
    }

    @Test
    fun loadRulesReplacesStaleEntries() {
        val dao = mockk<FirewallDao>(relaxed = true)
        every { dao.getAllRules() } returns flowOf(listOf(packageRule(packageName = "com.old")))
        val manager = FirewallRulesManager(FirewallRepository(dao))
        runBlocking { manager.loadRules() }
        assertEquals(setOf("com.old"), manager.getRulesSnapshot().keys)

        every { dao.getAllRules() } returns flowOf(listOf(packageRule(packageName = "com.new")))
        runBlocking { manager.loadRules() }
        assertEquals(setOf("com.new"), manager.getRulesSnapshot().keys)
        assertNull(manager.getRuleForPackage("com.old"))
    }

    @Test
    fun unknownUidHasNoPackages() {
        val manager = managerWith(packageRule())
        assertTrue(manager.getPackageNamesForUid(10123).isEmpty())
        assertNull(manager.getPackageNameForUid(10123))
    }
}
