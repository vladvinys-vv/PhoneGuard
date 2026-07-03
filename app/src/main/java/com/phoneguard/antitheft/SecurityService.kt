package com.phoneguard.antitheft

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Vibrator
import android.os.VibratorManager
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface SecurityService {
    suspend fun isLostModeActive(): Flow<Boolean>
    suspend fun activateLostMode()
    suspend fun sendLocationToContacts()
    suspend fun getTrustedContacts(): List<String>
}

@Singleton
class AntiTheftSecurityServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) : SecurityService {
    private val _lostModeActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    override suspend fun isLostModeActive(): Flow<Boolean> = _lostModeActive

    override suspend fun activateLostMode() {
        _lostModeActive.value = true
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val player = MediaPlayer.create(context, ringtone)
        player.isLooping = true
        player.start()
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(longArrayOf(0, 500, 1000), 0)
        preferencesManager.setRemoteAlarmEnabled(true)
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