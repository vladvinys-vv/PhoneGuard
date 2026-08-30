package com.phoneguard.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {

    private lateinit var networkMonitor: NetworkMonitor
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
        every { context.getSystemService<ConnectivityManager>() } returns connectivityManager
        networkMonitor = NetworkMonitor(context)
    }

    @Test
    fun `isNetworkAvailable emits true when network is available`() = runTest {
        val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities

        val result = networkMonitor.isNetworkAvailable().first()
        assertTrue(result)
    }

    @Test
    fun `isNetworkAvailable emits false when network is unavailable`() = runTest {
        val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns false
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities

        val result = networkMonitor.isNetworkAvailable().first()
        assertFalse(result)
    }
}
