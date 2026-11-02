package com.phoneguard.firewall

import com.phoneguard.model.FirewallRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Снимок правил фаервола: кого блокировать сейчас и какой пакет стоит за UID.
 *
 * Раньше вместо этого жил `FirewallRulesManagerTest` на MockK: он не исполнялся в JVM ни разу,
 * и матрица «попадёт ли приложение в TUN» на деле не проверялась. Логика вынесена в
 * [FirewallRuleIndex] (чистые данные, без Room и `PackageManager`), сама матрица правила —
 * в [com.phoneguard.model.blocksAllTraffic] и проверяется в `FirewallRulePolicyTest`.
 */
class FirewallRuleIndexTest {

    private fun rule(
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

    @Test
    fun defaultRuleAllowsTrafficOnBothNetworks() {
        val index = FirewallRuleIndex(listOf(rule()))
        assertTrue(index.blockedPackages(isOnWifi = true).isEmpty())
        assertTrue(index.blockedPackages(isOnWifi = false).isEmpty())
    }

    @Test
    fun blockAllBeatsNetworkType() {
        val index = FirewallRuleIndex(listOf(rule(blockAll = true)))
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = true))
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = false))
    }

    @Test
    fun blockWifiOnlyAffectsWifi() {
        val index = FirewallRuleIndex(listOf(rule(blockWifi = true)))
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = true))
        assertTrue(index.blockedPackages(isOnWifi = false).isEmpty())
    }

    @Test
    fun blockMobileOnlyAffectsMobile() {
        val index = FirewallRuleIndex(listOf(rule(blockMobile = true)))
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = false))
        assertTrue(index.blockedPackages(isOnWifi = true).isEmpty())
    }

    @Test
    fun wifiOnlyRuleBlocksMobileTraffic() {
        val index = FirewallRuleIndex(listOf(rule(allowWifiOnly = true)))
        assertTrue(index.blockedPackages(isOnWifi = true).isEmpty())
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = false))
    }

    @Test
    fun mobileOnlyRuleBlocksWifiTraffic() {
        val index = FirewallRuleIndex(listOf(rule(allowMobileOnly = true)))
        assertTrue(index.blockedPackages(isOnWifi = false).isEmpty())
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = true))
    }

    @Test
    fun denyByDefaultBlocksEverything() {
        val index = FirewallRuleIndex(listOf(rule(allowByDefault = false)))
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = true))
        assertEquals(setOf("com.example.app"), index.blockedPackages(isOnWifi = false))
    }

    @Test
    fun blockedPackagesReturnsOnlyFullyBlockedApps() {
        val index = FirewallRuleIndex(
            listOf(
                rule(packageName = "com.blocked.all", blockAll = true),
                rule(packageName = "com.blocked.mobile", blockMobile = true),
                rule(packageName = "com.allowed")
            )
        )
        assertEquals(
            setOf("com.blocked.all", "com.blocked.mobile"),
            index.blockedPackages(isOnWifi = false)
        )
        assertEquals(setOf("com.blocked.all"), index.blockedPackages(isOnWifi = true))
    }

    @Test
    fun onlyOneRulePerPackageIsKept() {
        // `associateBy` берёт последнее правило: дубликат ключа в кэше не должен давать
        // два разных ответа на вопрос «блокировать ли этот пакет».
        val index = FirewallRuleIndex(
            listOf(rule(packageName = "com.example.app"), rule(packageName = "com.example.app", blockAll = true))
        )
        assertEquals(1, index.snapshot().size)
        assertTrue(index.ruleFor("com.example.app")!!.blockAll)
    }

    @Test
    fun sharedUidKeepsAllPackages() {
        // Главный исторический баг: на UID хранился ОДИН пакет, и правило одного
        // приложения применялось к другому приложению с тем же UID.
        val index = FirewallRuleIndex(
            uidPackages = FirewallRuleIndex.groupByUid(
                listOf(10123 to "com.first", 10123 to "com.second", 10456 to "com.other")
            )
        )
        assertEquals(setOf("com.first", "com.second"), index.packagesForUid(10123).toSet())
        assertEquals(listOf("com.other"), index.packagesForUid(10456))
        assertTrue(index.firstPackageForUid(10123) in setOf("com.first", "com.second"))
    }

    @Test
    fun unknownUidHasNoPackages() {
        val index = FirewallRuleIndex(listOf(rule()), FirewallRuleIndex.groupByUid(listOf(10123 to "com.first")))
        assertTrue(index.packagesForUid(99999).isEmpty())
        assertNull(index.firstPackageForUid(99999))
    }

    @Test
    fun snapshotIsACopyAndCannotCorruptTheCache() {
        val index = FirewallRuleIndex(listOf(rule()))
        val snapshot = index.snapshot()
        (snapshot as MutableMap).clear()
        assertTrue("кэш не должен очищаться через снимок", index.snapshot().isNotEmpty())
        assertTrue(index.ruleFor("com.example.app") != null)
    }

    @Test
    fun updatingRulesKeepsUidMapping() {
        val index = FirewallRuleIndex(uidPackages = FirewallRuleIndex.groupByUid(listOf(10123 to "com.first")))
            .withRules(listOf(rule(packageName = "com.old")))
        assertEquals(listOf("com.first"), index.packagesForUid(10123))
        assertTrue(index.ruleFor("com.old") != null)

        val replaced = index.withRules(listOf(rule(packageName = "com.new")))
        assertNull("старое правило не должно оставаться после перезагрузки", replaced.ruleFor("com.old"))
        assertEquals(setOf("com.new"), replaced.snapshot().keys)
        assertEquals(listOf("com.first"), replaced.packagesForUid(10123))
    }

    @Test
    fun updatingUidMappingKeepsRules() {
        val index = FirewallRuleIndex(listOf(rule()))
            .withUidPackages(FirewallRuleIndex.groupByUid(listOf(10123 to "com.first")))
        assertTrue(index.ruleFor("com.example.app") != null)
        assertEquals(listOf("com.first"), index.packagesForUid(10123))
        assertFalse(index.blockedPackages(isOnWifi = true).contains("com.first"))
    }
}
