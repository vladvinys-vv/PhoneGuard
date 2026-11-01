package com.phoneguard.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

/**
 * Получение координат для anti-theft.
 *
 * Раньше `getLastLocation()` ждал `CountDownLatch` **без таймаута**: если
 * FusedLocationProvider не присылал ни успех, ни ошибку (типичная ситуация без
 * сети и без свежего фикса), вызов висел вечно и держал поток. Кроме того,
 * результат `lastLocation` запрашивался в отдельную переменную и не
 * использовался. Теперь все ожидания ограничены по времени, а последняя
 * известная точка используется как быстрый путь.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Возвращает последнюю известную точку, при отсутствии — свежую (с таймаутом). */
    suspend fun getLastLocation(): Location? {
        if (!hasLocationPermission()) return null

        val previous = withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MS) {
            runCatching {
                LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            }.getOrNull()
        }
        if (previous != null) return previous

        return requestFreshLocation()
    }

    /**
     * Запрашивает одно обновление местоположения.
     *
     * @param timeoutMs максимальное время ожидания; по истечении возвращается `null`.
     */
    suspend fun requestFreshLocation(timeoutMs: Long = FRESH_LOCATION_TIMEOUT_MS): Location? =
        suspendCancellableCoroutine { cont ->
            // PRIORITY_HIGH_ACCURACY при выданном только «примерном» разрешении
            // бросает SecurityException, поэтому точность выбирается по разрешениям.
            val accuracy = LocationAccuracyPolicy.accuracyFor(
                fineGranted = hasFineLocationPermission(),
                coarseGranted = hasCoarseLocationPermission()
            )
            if (accuracy == LocationAccuracyPolicy.Accuracy.NONE) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val handler = Handler(Looper.getMainLooper())
            var finished = false
            lateinit var timeout: Runnable

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacks(timeout)
                    runCatching { fusedClient.removeLocationUpdates(this) }
                    if (cont.isActive) cont.resume(result.lastLocation)
                }
            }

            timeout = Runnable {
                if (!finished) {
                    finished = true
                    runCatching { fusedClient.removeLocationUpdates(callback) }
                    if (cont.isActive) cont.resume(null)
                }
            }

            cont.invokeOnCancellation {
                finished = true
                handler.removeCallbacks(timeout)
                runCatching { fusedClient.removeLocationUpdates(callback) }
            }

            val priority = when (accuracy) {
                LocationAccuracyPolicy.Accuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
                else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val locationRequest = LocationRequest.Builder(priority, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
                .setMaxUpdates(1)
                .build()

            try {
                @Suppress("MissingPermission")
                fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
                handler.postDelayed(timeout, timeoutMs)
            } catch (_: SecurityException) {
                finished = true
                if (cont.isActive) cont.resume(null)
            } catch (_: Exception) {
                finished = true
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() || hasCoarseLocationPermission()

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val LAST_LOCATION_TIMEOUT_MS = 3_000L
        private const val FRESH_LOCATION_TIMEOUT_MS = 15_000L
        private const val UPDATE_INTERVAL_MS = 1_000L
    }
}
