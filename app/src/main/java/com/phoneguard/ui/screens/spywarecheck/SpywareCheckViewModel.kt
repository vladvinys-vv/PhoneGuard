package com.phoneguard.ui.screens.spywarecheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.repository.SpywareCheckRepository
import com.phoneguard.model.SpywareScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Экран проверки шпионского ПО.
 *
 * Исправления:
 *  - повторное сканирование больше не оставляет параллельные подписки: каждый вызов
 *    отменяет предыдущую работу (раньше несколько нажатий кнопки запускали несколько
 *    сборщиков одного и того же потока);
 *  - ошибка сканирования больше не оставляет бесконечный индикатор загрузки —
 *    состояние сбрасывается в `finally`.
 */
@HiltViewModel
class SpywareCheckViewModel @Inject constructor(
    private val repository: SpywareCheckRepository
) : ViewModel() {

    private val _scanResult = MutableStateFlow(
        SpywareScanResult(
            apps = emptyList(),
            totalRiskScore = 0,
            deviceAdminApps = emptyList(),
            accessibilityServices = emptyList(),
            sideloadedApps = emptyList(),
            hiddenApps = emptyList()
        )
    )
    val scanResult: StateFlow<SpywareScanResult> = _scanResult.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var scanJob: Job? = null

    init {
        scanForSpyware()
    }

    fun scanForSpyware() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.scanForSpyware().collect { result ->
                    _scanResult.value = result
                    _isLoading.value = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Скан мог упасть, например, из-за отказа PackageManager:
                // показываем то, что уже есть, и гасим индикатор.
                _isLoading.value = false
            } finally {
                // Отменённый скан не должен гасить индикатор нового (повторное
                // нажатие «Сканировать» отменяет предыдущую работу).
                if (isActive) _isLoading.value = false
            }
        }
    }
}
