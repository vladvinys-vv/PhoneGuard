package com.phoneguard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Матрица «весь трафик приложения заблокирован».
 *
 * Та же логика есть в `FirewallRulesManagerTest`, но там нужен MockK-репозиторий и она не
 * исполняется в среде без Gradle. Здесь проверка чистая — и потому реально выполняется.
 * Цена ошибки: лишний `true` — приложение молча остаётся без сети, лишний `false` — трафик
 * уходит в обход файрвола, то есть утечка.
 */
class FirewallRulePolicyTest {

    private fun rule(
        blockWifi: Boolean = false,
        blockMobile: Boolean = false,
        blockAll: Boolean = false,
        allowByDefault: Boolean = true,
        allowWifiOnly: Boolean = false,
        allowMobileOnly: Boolean = false
    ) = FirewallRule(
        packageName = "com.example.app",
        appName = "Example",
        blockWifi = blockWifi,
        blockMobile = blockMobile,
        blockAll = blockAll,
        allowByDefault = allowByDefault,
        allowWifiOnly = allowWifiOnly,
        allowMobileOnly = allowMobileOnly
    )

    @Test
    fun `default rule allows both networks`() {
        assertFalse(rule().blocksAllTraffic(isOnWifi = true))
        assertFalse(rule().blocksAllTraffic(isOnWifi = false))
    }

    @Test
    fun `blockAll wins over network type`() {
        assertTrue(rule(blockAll = true).blocksAllTraffic(isOnWifi = true))
        assertTrue(rule(blockAll = true).blocksAllTraffic(isOnWifi = false))
    }

    @Test
    fun `blockWifi affects only wifi`() {
        assertTrue(rule(blockWifi = true).blocksAllTraffic(isOnWifi = true))
        assertFalse(rule(blockWifi = true).blocksAllTraffic(isOnWifi = false))
    }

    @Test
    fun `blockMobile affects only mobile`() {
        assertTrue(rule(blockMobile = true).blocksAllTraffic(isOnWifi = false))
        assertFalse(rule(blockMobile = true).blocksAllTraffic(isOnWifi = true))
    }

    @Test
    fun `denyByDefault blocks everything regardless of flags`() {
        assertTrue(rule(allowByDefault = false).blocksAllTraffic(isOnWifi = true))
        assertTrue(rule(allowByDefault = false).blocksAllTraffic(isOnWifi = false))
    }

    @Test
    fun `wifiOnly rule blocks mobile`() {
        assertTrue(rule(allowWifiOnly = true).blocksAllTraffic(isOnWifi = false))
        assertFalse(rule(allowWifiOnly = true).blocksAllTraffic(isOnWifi = true))
    }

    @Test
    fun `mobileOnly rule blocks wifi`() {
        assertTrue(rule(allowMobileOnly = true).blocksAllTraffic(isOnWifi = true))
        assertFalse(rule(allowMobileOnly = true).blocksAllTraffic(isOnWifi = false))
    }

    @Test
    fun `contradictory allow only flags block on both networks`() {
        // Такого состояния UI не создаёт, но импорт настроек или старая база могут:
        // «только Wi-Fi» и «только мобильный» одновременно означают «никуда нельзя».
        val contradictory = rule(allowWifiOnly = true, allowMobileOnly = true)
        assertTrue(contradictory.blocksAllTraffic(isOnWifi = true))
        assertTrue(contradictory.blocksAllTraffic(isOnWifi = false))
    }

    @Test
    fun `wifiOnly plus blockWifi blocks on both networks`() {
        // «Только Wi-Fi» и «блокировать Wi-Fi» одновременно: на Wi-Fi срабатывает blockWifi,
        // на мобильном — allowWifiOnly. Сеть остаётся только у пользователя, который снимет
        // один из флагов; трафик мимо файрвола не уходит.
        val wifiOnlyButBlocked = rule(blockWifi = true, allowWifiOnly = true)
        assertTrue(wifiOnlyButBlocked.blocksAllTraffic(isOnWifi = true))
        assertTrue(wifiOnlyButBlocked.blocksAllTraffic(isOnWifi = false))
    }
}
