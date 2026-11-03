package com.phoneguard.util

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Читает тип активной сети из Android и отдаёт его чистой [NetworkTransportPolicy].
 *
 * Здесь только вызовы API и защита от исключений; решение «что считать Wi-Fi» принимает
 * политика, у которой есть тесты. Ключевая деталь — сети **без** транспорта `VPN`:
 * когда поднят собственный туннель (или чужой VPN), `activeNetwork` — это туннель, и
 * `hasTransport(WIFI)` у него ложно, из-за чего правила Wi-Fi переставали работать.
 */
internal object NetworkTypeResolver {

    fun currentTransport(connectivityManager: ConnectivityManager?): NetworkTransport {
        if (connectivityManager == null) return NetworkTransport.NONE

        val allCapabilities = runCatching {
            connectivityManager.allNetworks
                .mapNotNull { network -> connectivityManager.getNetworkCapabilities(network) }
                .filter { capabilities ->
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
        }.getOrDefault(emptyList())

        val withoutTunnel = allCapabilities.filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val relevant = if (withoutTunnel.isNotEmpty()) withoutTunnel else allCapabilities

        if (relevant.isNotEmpty()) {
            return NetworkTransportPolicy.choose(relevant.map { classify(it) })
        }

        // Список сетей пуст (нет разрешения/нет сети) — последняя попытка через активную сеть.
        val activeCapabilities = runCatching {
            connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        }.getOrNull()

        return activeCapabilities?.let { classify(it) } ?: NetworkTransport.NONE
    }

    fun isOnWifi(connectivityManager: ConnectivityManager?): Boolean =
        NetworkTransportPolicy.isOnWifi(currentTransport(connectivityManager))

    private fun classify(capabilities: NetworkCapabilities): NetworkTransport =
        NetworkTransportPolicy.classify(
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        )
}
