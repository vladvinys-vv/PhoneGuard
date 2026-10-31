package com.phoneguard.util

import android.net.Network
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Учёт нескольких активных сетей: Wi-Fi и мобильный интернет живут одновременно,
 * и потеря одной из них не должна выглядеть как «интернета нет».
 */
class NetworkAvailabilityTrackerTest {

    private val wifi: Network = mockk()
    private val mobile: Network = mockk()

    @Test
    fun availableNetworkMeansOnline() {
        val tracker = NetworkAvailabilityTracker()
        assertFalse(tracker.isAvailable)
        assertTrue(tracker.onAvailable(wifi))
    }

    @Test
    fun losingOneOfTwoNetworksKeepsOnline() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)
        tracker.onAvailable(mobile)

        assertTrue("мобильная сеть ещё работает — интернет есть", tracker.onLost(wifi))
        assertFalse(tracker.onLost(mobile))
    }

    @Test
    fun capabilitiesLossMarksNetworkOffline() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)
        assertFalse(tracker.onCapabilities(wifi, hasInternet = false))
        assertTrue(tracker.onCapabilities(wifi, hasInternet = true))
    }

    @Test
    fun losingUnknownNetworkDoesNotChangeState() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)
        assertTrue(tracker.onLost(mobile))
    }

    @Test
    fun seedWithoutInternetStaysOffline() {
        val tracker = NetworkAvailabilityTracker()
        assertFalse(tracker.seed(wifi, hasInternet = false))
        assertFalse(tracker.seed(null, hasInternet = true))
        assertTrue(tracker.seed(wifi, hasInternet = true))
    }
}
