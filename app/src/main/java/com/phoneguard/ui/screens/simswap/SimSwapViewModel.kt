package com.phoneguard.ui.screens.simswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.data.local.SimSwapEventDao
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SimSwapViewModel @Inject constructor(
    private val dao: SimSwapEventDao,
    private val preferencesManager: PreferencesManager,
    private val securityService: SecurityService
) : ViewModel() {

    data class UiState(
        val pendingEventId: Long? = null,
        val timerSeconds: Int = 300,
        val isCountingDown: Boolean = false,
        val lastSwapTimestamp: Long? = null,
        val isAttackSuspected: Boolean = false,
        val isConfirmed: Boolean = false,
        val history: List<com.phoneguard.model.SimSwapEvent> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            dao.observeAll().collect { list ->
                val latest = list.firstOrNull()
                _uiState.update {
                    it.copy(
                        history = list,
                        lastSwapTimestamp = latest?.timestamp,
                        isAttackSuspected = latest?.isAttackSuspected ?: false,
                        isConfirmed = latest?.isConfirmed ?: true,
                        pendingEventId = latest?.takeIf { ev -> !ev.isConfirmed }?.id
                    )
                }
            }
        }
        refreshPending()
    }

    fun refreshPending() {
        viewModelScope.launch {
            val latest = dao.latest() ?: return@launch
            val unconfirmed = !latest.isConfirmed
            _uiState.update {
                it.copy(
                    pendingEventId = if (unconfirmed) latest.id else null,
                    isConfirmed = !unconfirmed,
                    isAttackSuspected = latest.isAttackSuspected,
                    timerSeconds = if (unconfirmed) 300 else 0,
                    isCountingDown = unconfirmed
                )
            }
            if (unconfirmed) startCountdown()
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var seconds = _uiState.value.timerSeconds
            while (seconds > 0) {
                delay(1000)
                seconds--
                _uiState.update { it.copy(timerSeconds = seconds) }
            }
            _uiState.update { it.copy(isCountingDown = false) }
            lockDevice()
        }
    }

    fun onConfirm() {
        viewModelScope.launch {
            _uiState.value.pendingEventId?.let { dao.markConfirmed(it) }
            preferencesManager.setSimSwapConfirmed(true)
            preferencesManager.setSimSwapUnconfirmed(false)
            countdownJob?.cancel()
            _uiState.update { it.copy(isCountingDown = false, pendingEventId = null) }
            _events.emit(Event.Confirmed)
        }
    }

    fun onReject() {
        viewModelScope.launch {
            _uiState.value.pendingEventId?.let { id ->
                dao.markConfirmed(id)
                dao.markAttackSuspected(id)
            }
            preferencesManager.setSimSwapConfirmed(true)
            preferencesManager.setSimSwapUnconfirmed(false)
            countdownJob?.cancel()
            securityService.activateLostMode()
            securityService.sendLocationToContacts()
            _uiState.update { it.copy(isCountingDown = false, pendingEventId = null) }
            _events.emit(Event.Rejected)
        }
    }

    private suspend fun lockDevice() {
        securityService.activateLostMode()
        securityService.sendLocationToContacts()
        _events.emit(Event.AutoLocked)
    }

    sealed interface Event {
        object Confirmed : Event
        object Rejected : Event
        object AutoLocked : Event
    }
}