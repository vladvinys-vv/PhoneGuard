package com.phoneguard.firewall

import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FirewallRulesManagerTest {

    private lateinit var manager: FirewallRulesManager
    private val repository: FirewallRepository = mockk()

    @Before
    fun setup() {
        manager = FirewallRulesManager(repository)
    }

    @Test
    fun `loadRules populates cache`() = runTest {
        val rules = listOf(
            FirewallRule(
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
        )
        every { repository.allRules } returns flowOf(rules)

        manager.loadRules()

        val cached = manager.getRuleForPackage("com.test")
        assertNotNull(cached)
        assertEquals("com.test", cached!!.packageName)
    }

    @Test
    fun `getRuleForPackage returns null for unknown package`() {
        assertNull(manager.getRuleForPackage("com.unknown"))
    }

    @Test
    fun `getPackageNameForUid returns null for unknown uid`() {
        assertNull(manager.getPackageNameForUid(99999))
    }
}
