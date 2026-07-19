package com.phoneguard.ui.screens.antitheft

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.antitheft.DeviceAdminReceiverImpl
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.domain.shouldersurfer.ShoulderSurferUseCase
import com.phoneguard.ui.common.Event
import com.phoneguard.ui.common.EventViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AntiTheftUiState(
    val hasPin: Boolean = false,
    val backupNumber: String = "",
    val isDeviceAdminEnabled: Boolean = false,
    val isSimLockEnabled: Boolean = false,
    val isPhotoEnabled: Boolean = false,
    val isRemoteAlarmEnabled: Boolean = false,
    val isShoulderSurferEnabled: Boolean = false,
    val isAlarmPlaying: Boolean = false,
    val isPro: Boolean = false,
    val isLoading: Boolean = false
)

@HiltViewModel
class AntiTheftViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val shoulderSurferUseCase: ShoulderSurferUseCase,
    @ApplicationContext private val context: Context
) : EventViewModel() {

    private val _uiState = MutableStateFlow(AntiTheftUiState())
    val uiState: StateFlow<AntiTheftUiState> = _uiState.asStateFlow()

    init {
        loadState()
        checkDeviceAdminStatus()
    }

    private fun loadState() {
        viewModelScope.launch {
            combine(
                preferencesManager.hasPinSet,
                preferencesManager.backupNumber,
                preferencesManager.isSimLockEnabled,
                preferencesManager.isPhotoOnFailedAttemptsEnabled,
                preferencesManager.isRemoteAlarmEnabled,
                preferencesManager.isShoulderSurferEnabled,
                preferencesManager.isPro
            ) { hasPin, backupNumber, simLock, photoEnabled, remoteAlarm, shoulderSurfer, isPro ->
                _uiState.update { state ->
                    state.copy(
                        hasPin = hasPin,
                        backupNumber = backupNumber ?: "",
                        isSimLockEnabled = simLock,
                        isPhotoEnabled = photoEnabled,
                        isRemoteAlarmEnabled = remoteAlarm,
                        isShoulderSurferEnabled = shoulderSurfer,
                        isPro = isPro
                    )
                }
            }.collect()
        }
    }

    fun checkDeviceAdminStatus() {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = DeviceAdminReceiverImpl.getComponentName(context)
        val isActive = devicePolicyManager.isAdminActive(componentName)
        _uiState.update { it.copy(isDeviceAdminEnabled = isActive) }
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            preferencesManager.savePinCode(pin)
            _uiState.update { it.copy(isLoading = false) }
            sendEvent(Event.ShowSnackbar("PIN установлен"))
        }
    }

    fun setBackupNumber(number: String) {
        viewModelScope.launch {
            preferencesManager.saveBackupNumber(number)
            sendEvent(Event.ShowSnackbar("Резервный номер сохранён"))
        }
    }

    fun toggleSimLock(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setSimLockEnabled(enabled)
        }
    }

    fun togglePhotoOnFailedAttempts(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setPhotoOnFailedAttemptsEnabled(enabled)
        }
    }

    fun toggleRemoteAlarm(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setRemoteAlarmEnabled(enabled)
        }
    }

    fun toggleShoulderSurfer(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setShoulderSurferEnabled(enabled)
            if (enabled) {
                shoulderSurferUseCase.start()
                sendEvent(Event.ShowSnackbar("Защита от подглядывания включена"))
            } else {
                shoulderSurferUseCase.stop()
                sendEvent(Event.ShowSnackbar("Защита от подглядывания выключена"))
            }
        }
    }

    fun triggerRemoteWipe() {
        sendEvent(Event.ShowSnackbar("Remote wipe requires FCM integration"))
    }

    fun triggerRemoteLock() {
        sendEvent(Event.ShowSnackbar("Remote lock requires Device Admin API"))
    }

    fun validatePhoneNumber(number: String): Boolean {
        return number.isNotBlank() && android.util.Patterns.PHONE.matcher(number).matches()
    }

    fun validatePin(pin: String): Boolean {
        return pin.length >= 4 && pin.all { it.isDigit() }
    }

    private var alarmPlayer: MediaPlayer? = null

    fun playAlarm() {
        if (alarmPlayer != null) return
        viewModelScope.launch {
            try {
                val alarmUri: Uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                alarmPlayer = MediaPlayer.create(context, alarmUri)?.apply {
                    isLooping = true
                    start()
                }
                _uiState.update { it.copy(isAlarmPlaying = true) }
                sendEvent(Event.ShowSnackbar(context.getString(R.string.alarm_started)))
            } catch (e: Exception) {
                sendEvent(Event.ShowSnackbar("Не удалось запустить сигнализацию"))
            }
        }
    }

    fun stopAlarm() {
        viewModelScope.launch {
            try {
                alarmPlayer?.stop()
                alarmPlayer?.release()
            } catch (e: Exception) {}
            alarmPlayer = null
            _uiState.update { it.copy(isAlarmPlaying = false) }
            sendEvent(Event.ShowSnackbar(context.getString(R.string.alarm_stopped)))
        }
    }
}
