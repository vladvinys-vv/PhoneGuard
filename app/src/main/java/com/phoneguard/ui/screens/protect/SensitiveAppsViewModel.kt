package com.phoneguard.ui.screens.protect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.protect.SensitiveAppGuard
import com.phoneguard.protect.SensitiveAppPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Экран «Защита приложений»: кто отмечен ценным и кто рядом умеет подглядывать.
 *
 * Состояние отдаётся одним снимком, а не пятью потоками: список защищённых, предложения и
 * опасные соседи считаются вместе — иначе после переключения одного приложения интерфейс
 * успевал показать его и в «защищённых», и в «предложениях».
 */
@HiltViewModel
class SensitiveAppsViewModel @Inject constructor(
    private val sensitiveAppGuard: SensitiveAppGuard
) : ViewModel() {

    /** Что показывать. */
    sealed class SensitiveAppsState {
        /** Список ещё собирается: перечисление приложений и чтение настроек — не мгновенно. */
        object SensitiveAppsLoading : SensitiveAppsState()

        /** Готовый снимок. */
        data class SensitiveAppsReady(val snapshot: SensitiveAppGuard.SensitiveAppsSnapshot) :
            SensitiveAppsState()

        /** Перечислить приложения не удалось; пустой список показывать нельзя — соврём. */
        object SensitiveAppsUnavailable : SensitiveAppsState()
    }

    private val _state = MutableStateFlow<SensitiveAppsState>(SensitiveAppsState.SensitiveAppsLoading)
    val state: StateFlow<SensitiveAppsState> = _state.asStateFlow()

    /**
     * Попытка добавить сверх предела. Отдельный флаг, потому что «ничего не произошло» —
     * худший ответ на нажатие переключателя.
     */
    private val _limitReached = MutableStateFlow(false)
    val limitReached: StateFlow<Boolean> = _limitReached.asStateFlow()

    /** Предел списка — для подсказки в интерфейсе. */
    val maxProtected: Int = SensitiveAppPolicy.MAX_PROTECTED

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = try {
                SensitiveAppsState.SensitiveAppsReady(sensitiveAppGuard.protectionSnapshot())
            } catch (e: Exception) {
                SensitiveAppsState.SensitiveAppsUnavailable
            }
        }
    }

    fun setProtected(packageName: String, isProtected: Boolean) {
        viewModelScope.launch {
            val result = try {
                sensitiveAppGuard.setProtected(packageName, isProtected)
            } catch (e: Exception) {
                null
            }
            // `null` — отказ (предел списка), а не сбой чтения: говорим об этом прямо.
            _limitReached.value = result == null && isProtected
            refresh()
        }
    }

    fun consumeLimitReached() {
        _limitReached.value = false
    }
}
