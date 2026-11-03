package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Классификация сети для правил фаервола.
 *
 * Главный случай — **собственный туннель**: как только VPN поднят, `activeNetwork` устройства
 * это наш туннель с транспортом `VPN`. Раньше тип сети определялся именно по нему, поэтому
 * `onWifi` всегда был `false`: правила `blockWifi` не срабатывали никогда, а `allowWifiOnly`
 * блокировал всё подряд. Теперь тип берётся по сетям без транспорта `VPN`, и это закреплено
 * тестами: «туннель + Wi-Fi» обязано остаться Wi-Fi.
 */
class NetworkTransportPolicyTest {

    // --- тип одной сети ------------------------------------------------------

    @Test
    fun wifiIsDetected() {
        assertEquals(NetworkTransport.WIFI, NetworkTransportPolicy.classify(wifi = true))
    }

    @Test
    fun concreteTransportBeatsTunnelTransport() {
        // Некоторые устройства отдают у VPN-сети и транспорт туннеля, и транспорт нижележащей сети.
        assertEquals(
            NetworkTransport.WIFI,
            NetworkTransportPolicy.classify(wifi = true, vpn = true)
        )
        assertEquals(
            NetworkTransport.CELLULAR,
            NetworkTransportPolicy.classify(cellular = true, vpn = true)
        )
    }

    @Test
    fun ethernetAndCellularAreDistinguished() {
        assertEquals(NetworkTransport.ETHERNET, NetworkTransportPolicy.classify(ethernet = true))
        assertEquals(NetworkTransport.CELLULAR, NetworkTransportPolicy.classify(cellular = true))
        assertEquals(
            NetworkTransport.ETHERNET,
            NetworkTransportPolicy.classify(ethernet = true, cellular = true)
        )
    }

    @Test
    fun tunnelWithoutUnderlyingTransportIsVpn() {
        assertEquals(NetworkTransport.VPN, NetworkTransportPolicy.classify(vpn = true))
    }

    @Test
    fun networkWithoutKnownTransportIsOther() {
        assertEquals(NetworkTransport.OTHER, NetworkTransportPolicy.classify())
    }

    // --- выбор из нескольких активных сетей ----------------------------------

    @Test
    fun noNetworksMeansNone() {
        assertEquals(NetworkTransport.NONE, NetworkTransportPolicy.choose(emptyList()))
    }

    @Test
    fun wifiWinsOverCellularWhenBothActive() {
        val chosen = NetworkTransportPolicy.choose(
            listOf(NetworkTransport.CELLULAR, NetworkTransport.WIFI)
        )
        assertEquals(NetworkTransport.WIFI, chosen)
    }

    @Test
    fun tunnelDoesNotHideUnderlyingWifi() {
        // Регрессия: при поднятом туннеле тип сети обязан остаться Wi-Fi.
        val chosen = NetworkTransportPolicy.choose(
            listOf(NetworkTransport.VPN, NetworkTransport.WIFI)
        )
        assertEquals(NetworkTransport.WIFI, chosen)
    }

    @Test
    fun tunnelOverCellularStaysMobile() {
        val chosen = NetworkTransportPolicy.choose(
            listOf(NetworkTransport.VPN, NetworkTransport.CELLULAR)
        )
        assertEquals(NetworkTransport.CELLULAR, chosen)
    }

    @Test
    fun ethernetBeatsCellular() {
        val chosen = NetworkTransportPolicy.choose(
            listOf(NetworkTransport.CELLULAR, NetworkTransport.ETHERNET)
        )
        assertEquals(NetworkTransport.ETHERNET, chosen)
    }

    @Test
    fun cellularBeatsUnknownNetworks() {
        val chosen = NetworkTransportPolicy.choose(
            listOf(NetworkTransport.OTHER, NetworkTransport.CELLULAR)
        )
        assertEquals(NetworkTransport.CELLULAR, chosen)
    }

    @Test
    fun onlyTunnelMeansTunnel() {
        val chosen = NetworkTransportPolicy.choose(
            listOf(NetworkTransport.VPN, NetworkTransport.VPN)
        )
        assertEquals(NetworkTransport.VPN, chosen)
    }

    @Test
    fun duplicatesDoNotChangeResult() {
        val chosen = NetworkTransportPolicy.choose(
            listOf(NetworkTransport.WIFI, NetworkTransport.WIFI, NetworkTransport.CELLULAR)
        )
        assertEquals(NetworkTransport.WIFI, chosen)
    }

    // --- какое правило применять ---------------------------------------------

    @Test
    fun onlyWifiUsesWifiRules() {
        assertEquals(
            FirewallNetworkType.WIFI,
            NetworkTransportPolicy.firewallType(NetworkTransport.WIFI)
        )
        assertTrue(NetworkTransportPolicy.isOnWifi(NetworkTransport.WIFI))
    }

    @Test
    fun everythingElseUsesMobileRules() {
        val others = listOf(
            NetworkTransport.ETHERNET,
            NetworkTransport.CELLULAR,
            NetworkTransport.VPN,
            NetworkTransport.OTHER,
            NetworkTransport.NONE
        )
        for (transport in others) {
            // Ethernet — сознательно «мобильный»: так было до выноса, а смена смысла
            // пользовательских правил (`blockMobile`) — продуктовое решение, не исправление.
            assertEquals(
                "транспорт $transport должен идти по мобильным правилам",
                FirewallNetworkType.MOBILE,
                NetworkTransportPolicy.firewallType(transport)
            )
            assertFalse(NetworkTransportPolicy.isOnWifi(transport))
        }
    }
}
