package com.phoneguard.ui.screens.spywarecheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.repository.SpywareCheckRepository
import com.phoneguard.model.SpywareScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    init {
        scanForSpyware()
    }

    fun scanForSpyware() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.scanForSpyware().collect { result ->
                _scanResult.value = result
                _isLoading.value = false
            }
        }
    }
}
