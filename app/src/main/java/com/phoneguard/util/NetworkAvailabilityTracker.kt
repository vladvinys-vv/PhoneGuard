package com.phoneguard.util

import android.net.Network

/**
 * Учёт одновременно активных сетей.
 *
 * Раньше `NetworkMonitor` считал наличие интернета по одному событию: `onLost` любой
 * сети отправлял `false`, даже если рядом работала другая (типичный случай —
 * телефон с Wi-Fi и мобильным интернетом: Wi-Fi пропал, а сеть осталась). Кроме
 * того, не учитывался `onCapabilitiesChanged`: сеть может остаться подключённой,
 * но потерять выход в интернет (captive portal, «подключено без доступа»).
 *
 * Логика вынесена в отдельный класс, чтобы покрывать её JVM-тестами без Android.
 */
internal class NetworkAvailabilityTracker {

    /**
     * Колбэки `ConnectivityManager` приходят в свой поток, а `isAvailable` читают
     * UI и воркеры. Без блокировки на некоторых устройствах чтение видело устаревшее
     * значение (JMM не гарантирует видимость записи из другого потока), и приложение
     * считало, что интернета нет, хотя сеть уже поднялась.
     */
    private val activeNetworks = LinkedHashSet<Network>()

    val isAvailable: Boolean get() = synchronized(this) { activeNetworks.isNotEmpty() }

    /** Сеть стала доступна. */
    @Synchronized
    fun onAvailable(network: Network): Boolean {
        activeNetworks += network
        return isAvailable
    }

    /** Сеть потеряна; остальные сети продолжают учитываться. */
    @Synchronized
    fun onLost(network: Network): Boolean {
        activeNetworks -= network
        return isAvailable
    }

    /** У сети изменились возможности — например, пропал доступ в интернет. */
    @Synchronized
    fun onCapabilities(network: Network, hasInternet: Boolean): Boolean {
        if (hasInternet) activeNetworks += network else activeNetworks -= network
        return isAvailable
    }

    /** Начальное состояние до прихода колбэков. */
    @Synchronized
    fun seed(network: Network?, hasInternet: Boolean): Boolean {
        if (network != null && hasInternet) activeNetworks += network
        return isAvailable
    }
}
