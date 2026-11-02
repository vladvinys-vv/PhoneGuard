package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Учёт нескольких активных сетей: Wi-Fi и мобильный интернет живут одновременно,
 * и потеря одной из них не должна выглядеть как «интернета нет».
 *
 * Ключ сети — параметр типа, поэтому тест работает на строках: ни `android.net.Network`,
 * ни MockK не нужны, файл исполняется в обычном JVM-прогоне.
 */
class NetworkAvailabilityTrackerTest {

    private val wifi = "wifi"
    private val mobile = "mobile"

    @Test
    fun availableNetworkMeansOnline() {
        val tracker = NetworkAvailabilityTracker<String>()
        assertFalse(tracker.isAvailable)
        assertTrue(tracker.onAvailable(wifi))
        assertTrue(tracker.isAvailable)
    }

    @Test
    fun losingOneOfTwoNetworksKeepsOnline() {
        val tracker = NetworkAvailabilityTracker<String>()
        tracker.onAvailable(wifi)
        tracker.onAvailable(mobile)

        assertTrue("мобильная сеть ещё работает — интернет есть", tracker.onLost(wifi))
        assertFalse(tracker.onLost(mobile))
    }

    @Test
    fun capabilitiesLossMarksNetworkOffline() {
        val tracker = NetworkAvailabilityTracker<String>()
        tracker.onAvailable(wifi)
        assertFalse(tracker.onCapabilities(wifi, hasInternet = false))
        assertTrue(tracker.onCapabilities(wifi, hasInternet = true))
    }

    @Test
    fun capabilitiesChangeOnUnknownNetworkTracksIt() {
        // Сеть может «появиться» только в onCapabilitiesChanged: часть прошивок
        // не присылает onAvailable до первого изменения возможностей.
        val tracker = NetworkAvailabilityTracker<String>()
        assertTrue(tracker.onCapabilities(mobile, hasInternet = true))
        assertEquals(true, tracker.isAvailable)
        assertFalse(tracker.onCapabilities(mobile, hasInternet = false))
    }

    @Test
    fun losingUnknownNetworkDoesNotChangeState() {
        val tracker = NetworkAvailabilityTracker<String>()
        tracker.onAvailable(wifi)
        assertTrue(tracker.onLost(mobile))
    }

    @Test
    fun repeatedAvailableEventsAreIdempotent() {
        // Повторный onAvailable для той же сети не должен «дублировать» её:
        // иначе один onLost оставит сеть в наборе и интернет будет считаться вечным.
        val tracker = NetworkAvailabilityTracker<String>()
        tracker.onAvailable(wifi)
        tracker.onAvailable(wifi)
        assertFalse(tracker.onLost(wifi))
        assertFalse(tracker.isAvailable)
    }

    @Test
    fun seedWithoutInternetStaysOffline() {
        val tracker = NetworkAvailabilityTracker<String>()
        assertFalse(tracker.seed(wifi, hasInternet = false))
        assertFalse(tracker.seed(null, hasInternet = true))
        assertTrue(tracker.seed(wifi, hasInternet = true))
    }

    @Test
    fun seedDoesNotDropNetworksFoundLater() {
        val tracker = NetworkAvailabilityTracker<String>()
        tracker.seed(wifi, hasInternet = true)
        tracker.onAvailable(mobile)
        assertTrue(tracker.onLost(wifi))
        assertFalse(tracker.onLost(mobile))
    }
}
