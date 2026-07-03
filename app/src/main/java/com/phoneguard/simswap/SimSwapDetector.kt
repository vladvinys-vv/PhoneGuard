package com.phoneguard.simswap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.data.local.SimSwapEventDao
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimSwapDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SimSwapEventDao,
    private val preferencesManager: PreferencesManager,
    private val securityService: SecurityService,
    private val scope: CoroutineScope = CoroutineScope(IO)
) {
    private val _uiEvents = MutableSharedFlow<SimSwapUiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<SimSwapUiEvent> = _uiEvents

    @SuppressLint("MissingPermission")
    fun onSimStateChanged() {
        scope.launch {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }

            val newImsi = telephonyManager.subscriberId
            val oldImsi = getLastKnownImsi()

            if (newImsi != null && newImsi != oldImsi && oldImsi != null) {
                val location = resolveLocation()
                val timestamp = System.currentTimeMillis()
                val event = SimSwapEvent(
                    timestamp = timestamp,
                    oldImsi = oldImsi,
                    newImsi = newImsi,
                    latitude = location.first,
                    longitude = location.second,
                    addressString = location.third,
                    isConfirmed = false,
                    isAttackSuspected = false
                )
                dao.insert(event)
                saveLastKnownImsi(newImsi)
                withContext(IO) {
                    preferencesManager.setSimSwapUnconfirmed(true)
                }
                _uiEvents.emit(SimSwapUiEvent.ShowConfirmation(event))
                sendBackupSms(event)
            }
        }
    }

    suspend fun confirmCurrentSimSwap() {
        val latest = dao.latest()
        latest?.let { dao.markConfirmed(it.id) }
        preferencesManager.setSimSwapConfirmed(true)
        preferencesManager.setSimSwapUnconfirmed(false)
        disableSensitiveAppsGuard()
    }

    suspend fun rejectCurrentSimSwap() {
        val latest = dao.latest()
        latest?.let {
            dao.markConfirmed(it.id)
            dao.markAttackSuspected(it.id)
        }
        preferencesManager.setSimSwapConfirmed(true)
        preferencesManager.setSimSwapUnconfirmed(false)
        activateLostMode()
    }

    private suspend fun activateLostMode() {
        securityService.activateLostMode()
        securityService.sendLocationToContacts()
    }

    private suspend fun sendBackupSms(event: SimSwapEvent): Boolean = withContext(IO) {
        val backupNumber = preferencesManager.backupNumber.first()
        if (backupNumber.isBlank()) return@withContext false
        val date = Date(event.timestamp)
        val message = buildString {
            append("SIM вашего телефона поменялась ")
            append(date)
            append(" в локации ")
            append(event.addressString ?: "неизвестная локация")
            append(". Подтвердите это срочной ссылкой/кодом, иначе приложение заблокируется через 5 минут.")
        }
        return@withContext try {
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getLastUnconfirmedEvent(): SimSwapEvent? = withContext(IO) {
        dao.latest()?.takeIf { !it.isConfirmed }
    }

    fun hasUnconfirmed(): Boolean = preferencesManager.isSimSwapUnconfirmed.first()

    private suspend fun getLastKnownImsi(): String? = withContext(IO) {
        preferencesManager.lastKnownImsi.first().ifEmpty { null }
    }

    private suspend fun saveLastKnownImsi(imsi: String?) = withContext(IO) {
        preferencesManager.setLastKnownImsi(imsi.orEmpty())
    }

    private suspend fun resolveLocation(): Triple<Double?, Double?, String?> = withContext(IO) {
        try {
            val fused = FusedLocationProviderClient(context)
            val location = fused.await() ?: return@withContext Triple(null, null, null)
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses: List<Address> = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            val address = addresses.firstOrNull()?.getAddressLine(0)
            Triple(location.latitude, location.longitude, address)
        } catch (e: Exception) {
            Triple(null, null, null)
        }
    }

    fun enableSensitiveAppsGuard() {
        if (!preferencesManager.isSensitiveAppsGuardEnabled.first()) return
        // Guard will be handled via accessibility observer in existing app lock flows
    }

    private fun disableSensitiveAppsGuard() {
        // noop post confirm
    }

    companion object {
        const val ACTION_STOP_CONFIRMATION = "com.phoneguard.simswap.ACTION_STOP_CONFIRMATION"
    }
}

sealed interface SimSwapUiEvent {
    data class ShowConfirmation(val event: SimSwapEvent) : SimSwapUiEvent
}

suspend fun FusedLocationProviderClient.await(): android.location.Location? = try {
    kotlinx.coroutines.tasks.await(lastLocation)
} catch (e: Exception) {
    null
}