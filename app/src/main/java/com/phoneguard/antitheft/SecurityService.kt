package com.phoneguard.antitheft

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Vibrator
import android.os.VibratorManager
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface SecurityService {
    suspend fun isLostModeActive(): Flow<Boolean>
    suspend fun activateLostMode()
    suspend fun stopAlarm()
    suspend fun sendLocationToContacts()
    suspend fun getTrustedContacts(): List<String>
}

@Singleton
class AntiTheftSecurityServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) : SecurityService {
    private val _lostModeActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var alarmPlayer: MediaPlayer? = null
    private var alarmVibrator: Vibrator? = null

    override suspend fun isLostModeActive(): Flow<Boolean> = _lostModeActive

    override suspend fun activateLostMode() {
        _lostModeActive.value = true
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val player = MediaPlayer.create(context, ringtone)
        player.isLooping = true
        player.start()
        alarmPlayer = player
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(longArrayOf(0, 500, 1000), 0)
        alarmVibrator = vibrator
        preferencesManager.setRemoteAlarmEnabled(true)
    }

    override suspend fun stopAlarm() {
        try { alarmPlayer?.stop() } catch (_: Exception) {}
        try { alarmPlayer?.release() } catch (_: Exception) {}
        alarmPlayer = null
        try { alarmVibrator?.cancel() } catch (_: Exception) {}
        alarmVibrator = null
        preferencesManager.setRemoteAlarmEnabled(false)
        _lostModeActive.value = false
    }

    override suspend fun sendLocationToContacts() {
        // Reuse existing Anti-Theft contacts logic from PreferencesManager
        val backupNumber = preferencesManager.backupNumber.first()
        // Placeholder for sending current location to trusted contacts
    }

    override suspend fun getTrustedContacts(): List<String> {
        val backupNumber = preferencesManager.backupNumber.first()
        return if (backupNumber.isBlank()) emptyList() else listOf(backupNumber)
    }
}