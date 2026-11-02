package com.phoneguard.util

/**
 * Учёт одновременно активных сетей.
 *
 * Раньше `NetworkMonitor` считал наличие интернета по одному событию: `onLost` любой
 * сети отправлял `false`, даже если рядом работала другая (типичный случай —
 * телефон с Wi-Fi и мобильным интернетом: Wi-Fi пропал, а сеть осталась). Кроме
 * того, не учитывался `onCapabilitiesChanged`: сеть может остаться подключённой,
 * но потерять выход в интернет (captive portal, «подключено без доступа»).
 *
 * Логика вынесена в отдельный класс, чтобы покрывать её JVM-тестами без Android:
 * ключ сети — параметр типа `N` (в приложении это `android.net.Network`, в тестах —
 * обычная строка), сам учёт от Android не зависит.
 */
internal class NetworkAvailabilityTracker<N : Any> {

    /**
     * Колбэки `ConnectivityManager` приходят в свой поток, а `isAvailable` читают
     * UI и воркеры. Без блокировки на некоторых устройствах чтение видело устаревшее
     * значение (JMM не гарантирует видимость записи из другого потока), и приложение
     * считало, что интернета нет, хотя сеть уже поднялась.
     */
    private val activeNetworks = LinkedHashSet<N>()

    val isAvailable: Boolean get() = synchronized(this) { activeNetworks.isNotEmpty() }

    /** Сеть стала доступна. */
    @Synchronized
    fun onAvailable(network: N): Boolean {
        activeNetworks += network
        return isAvailable
    }

    /** Сеть потеряна; остальные сети продолжают учитываться. */
    @Synchronized
    fun onLost(network: N): Boolean {
        activeNetworks -= network
        return isAvailable
    }

    /** У сети изменились возможности — например, пропал доступ в интернет. */
    @Synchronized
    fun onCapabilities(network: N, hasInternet: Boolean): Boolean {
        if (hasInternet) activeNetworks += network else activeNetworks -= network
        return isAvailable
    }

    /** Начальное состояние до прихода колбэков. */
    @Synchronized
    fun seed(network: N?, hasInternet: Boolean): Boolean {
        if (network != null && hasInternet) activeNetworks += network
        return isAvailable
    }
}
