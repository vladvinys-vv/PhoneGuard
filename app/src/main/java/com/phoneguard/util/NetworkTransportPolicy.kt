package com.phoneguard.util

/** Тип сети без Android: ровно то, что различает фаервол и журнал блокировок. */
internal enum class NetworkTransport { WIFI, ETHERNET, CELLULAR, VPN, OTHER, NONE }

/** Какой набор правил фаервола применять к текущей сети. */
internal enum class FirewallNetworkType { WIFI, MOBILE }

/**
 * Классификация активной сети для правил фаервола — чистые правила, без `ConnectivityManager`.
 *
 * Зачем это вынесено: `PhoneGuardVpnService.isOnWifi()` смотрел только
 * `activeNetwork.hasTransport(TRANSPORT_WIFI)`. Но как только **свой** туннель поднят,
 * `activeNetwork` — это наш VPN, у которого транспорт `VPN`, а не `WIFI`. Значит `onWifi`
 * всегда получался `false`: правила `blockWifi` не срабатывали никогда, а `allowWifiOnly`
 * блокировал всё подряд — то есть Wi-Fi-часть матрицы правил не работала вовсе.
 *
 * Решение: сеть-туннель не считается типом сети. Сначала берётся лучшая сеть **без**
 * транспорта `VPN` (у неё и спрашиваем `WIFI`/`CELLULAR`/`ETHERNET`), и только если такой
 * нет — используется туннель. Выбор «лучшей» — тоже правило, поэтому оно здесь, а не в сервисе.
 *
 * Ethernet намеренно остаётся «мобильным» типом: так было и до выноса, а его перевод в
 * Wi-Fi меняет смысл пользовательских правил (`blockMobile`) — это продуктовое решение,
 * а не исправление.
 */
internal object NetworkTransportPolicy {

    /**
     * Тип одной сети по её возможностям.
     *
     * Порядок важен: некоторые устройства отдают у VPN-сети и транспорт туннеля, и
     * транспорт нижележащей сети — тогда верим конкретному транспорту.
     */
    fun classify(
        wifi: Boolean = false,
        ethernet: Boolean = false,
        cellular: Boolean = false,
        vpn: Boolean = false
    ): NetworkTransport = when {
        wifi -> NetworkTransport.WIFI
        ethernet -> NetworkTransport.ETHERNET
        cellular -> NetworkTransport.CELLULAR
        vpn -> NetworkTransport.VPN
        else -> NetworkTransport.OTHER
    }

    /**
     * Тип сети из набора активных сетей.
     *
     * Предпочтение: Wi-Fi → Ethernet → сотовый → прочее. Пустой набор (нет сети вообще или
     * нет разрешения на чтение состояния) — [NetworkTransport.NONE].
     */
    fun choose(transports: Collection<NetworkTransport>): NetworkTransport = when {
        transports.isEmpty() -> NetworkTransport.NONE
        NetworkTransport.WIFI in transports -> NetworkTransport.WIFI
        NetworkTransport.ETHERNET in transports -> NetworkTransport.ETHERNET
        NetworkTransport.CELLULAR in transports -> NetworkTransport.CELLULAR
        NetworkTransport.VPN in transports -> NetworkTransport.VPN
        else -> NetworkTransport.OTHER
    }

    /** Тип сети, который видит пользователь в правилах: туннель и «прочее» — мобильные. */
    fun firewallType(transport: NetworkTransport): FirewallNetworkType =
        if (transport == NetworkTransport.WIFI) FirewallNetworkType.WIFI else FirewallNetworkType.MOBILE

    fun isOnWifi(transport: NetworkTransport): Boolean =
        firewallType(transport) == FirewallNetworkType.WIFI
}
