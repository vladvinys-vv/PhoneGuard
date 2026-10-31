package com.phoneguard.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Наличие интернета как поток.
     *
     * Состояние собирается из набора активных сетей (см. [NetworkAvailabilityTracker]):
     * потеря одной сети при работающей другой не считается «интернета нет», а сеть
     * без `NET_CAPABILITY_INTERNET` сразу перестаёт учитываться.
     */
    fun isNetworkAvailable(): Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        if (connectivityManager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val tracker = NetworkAvailabilityTracker()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(tracker.onAvailable(network))
            }

            override fun onLost(network: Network) {
                trySend(tracker.onLost(network))
            }

            override fun onUnavailable() {
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(
                    tracker.onCapabilities(
                        network,
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    )
                )
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Регистрация может быть отклонена (лимит колбэков, отозванное разрешение).
        // Раньше исключение вылетало из callbackFlow и роняло сборщика потока.
        val registered = runCatching { connectivityManager.registerNetworkCallback(request, callback) }.isSuccess
        if (!registered) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val currentNetwork = connectivityManager.activeNetwork
        val hasInternet = currentNetwork != null &&
            connectivityManager.getNetworkCapabilities(currentNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(tracker.seed(currentNetwork, hasInternet))

        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
