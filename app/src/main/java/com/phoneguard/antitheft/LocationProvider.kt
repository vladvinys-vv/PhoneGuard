package com.phoneguard.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Location Provider для anti-theft.
 * Использует Fused Location Provider для получения координат.
 */
class LocationProvider(private val context: Context) {

    fun getLastLocation(): Location? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return null

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        @Suppress("MissingPermission")
        val locationTask = fusedClient.lastLocation
        // lastLocation — async, но для простоты используем await в coroutine
        // В реальном коде лучше использовать callback
        return try {
            // Синхронное ожидание (не ideal, но работает для anti-theft)
            val result = java.util.concurrent.CountDownLatch(1)
            var resultLocation: Location? = null
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    resultLocation = loc
                    result.countDown()
                }
                .addOnFailureListener {
                    result.countDown()
                }
            result.await()
            resultLocation
        } catch (_: Exception) {
            null
        }
    }

    suspend fun requestFreshLocation(timeoutMs: Long = 15000): Location? = suspendCancellableCoroutine { cont ->
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, timeoutMs)
            .setMinUpdateIntervalMillis(5000)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedClient.removeLocationUpdates(this)
                val location = result.lastLocation
                if (cont.isActive) {
                    cont.resume(location)
                }
            }
        }

        try {
            @Suppress("MissingPermission")
            fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())

            // Timeout
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (cont.isActive) {
                    fusedClient.removeLocationUpdates(callback)
                    cont.resume(null)
                }
            }, timeoutMs)
        } catch (e: SecurityException) {
            cont.resume(null)
        }
    }
}
