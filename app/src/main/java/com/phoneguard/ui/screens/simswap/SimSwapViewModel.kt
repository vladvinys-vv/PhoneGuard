package com.phoneguard.ui.screens.simswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.data.local.SimSwapEventDao
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
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

    /**
     * События экрана подтверждения.
     *
     * `MutableSharedFlow` без буфера терял событие, отправленное до подписки
     * (поворот экрана, повторный вход), а `emit` без подписчика вообще зависал.
     * `Channel` доставляет событие ровно один раз и не требует ожидания.
     */
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

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
            if (unconfirmed) startCountdown(latest.timestamp + AUTO_LOCK_WINDOW_MS)
        }
    }

    /**
     * Обратный отсчёт до автоматической блокировки.
     *
     * Считается от времени события, а не от 300 секунд «с нуля»: раньше при каждом
     * открытии экрана (в том числе после поворота) таймер начинался заново, и
     * автоблокировка могла не сработать никогда.
     */
    private fun startCountdown(deadlineMillis: Long) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (true) {
                val remaining = ((deadlineMillis - System.currentTimeMillis()) / 1000)
                    .coerceAtLeast(0L)
                    .coerceAtMost(AUTO_LOCK_WINDOW_MS / 1000)
                    .toInt()
                _uiState.update { it.copy(timerSeconds = remaining, isCountingDown = remaining > 0) }
                if (remaining == 0) break
                delay(1000)
            }
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
            _events.trySend(Event.Confirmed)
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
            _events.trySend(Event.Rejected)
        }
    }

    private suspend fun lockDevice() {
        securityService.activateLostMode()
        securityService.sendLocationToContacts()
        _events.trySend(Event.AutoLocked)
    }

    sealed interface Event {
        object Confirmed : Event
        object Rejected : Event
        object AutoLocked : Event
    }

    private companion object {
        /** Сколько пользователь может подтвердить «свой» SIM перед автоблокировкой. */
        const val AUTO_LOCK_WINDOW_MS = 300_000L
    }
}