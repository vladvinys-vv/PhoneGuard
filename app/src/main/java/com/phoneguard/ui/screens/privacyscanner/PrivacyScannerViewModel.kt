package com.phoneguard.ui.screens.privacyscanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.repository.PrivacyScannerRepository
import com.phoneguard.model.PrivacyApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed class PrivacyFilter {
    object All : PrivacyFilter()
    object Excessive : PrivacyFilter()
    object RecentlyUsedCamera : PrivacyFilter()
    object RecentlyUsedMic : PrivacyFilter()
}

@HiltViewModel
class PrivacyScannerViewModel @Inject constructor(
    private val repository: PrivacyScannerRepository
) : ViewModel() {
    private val _apps = MutableStateFlow<List<PrivacyApp>>(emptyList())
    val apps: StateFlow<List<PrivacyApp>> = _apps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedFilter = MutableStateFlow<PrivacyFilter>(PrivacyFilter.All)
    val selectedFilter: StateFlow<PrivacyFilter> = _selectedFilter.asStateFlow()

    val filteredApps: StateFlow<List<PrivacyApp>> = combine(apps, selectedFilter) { apps, filter ->
        when (filter) {
            PrivacyFilter.All -> apps
            PrivacyFilter.Excessive -> apps.filter { it.hasExcessivePermissions }
            PrivacyFilter.RecentlyUsedCamera -> apps.filter { app ->
                app.permissions.any { it.name == android.Manifest.permission.CAMERA && it.lastUsedTime != null }
            }.sortedByDescending {
                it.permissions.find { perm -> perm.name == android.Manifest.permission.CAMERA }?.lastUsedTime
            }
            PrivacyFilter.RecentlyUsedMic -> apps.filter { app ->
                app.permissions.any { it.name == android.Manifest.permission.RECORD_AUDIO && it.lastUsedTime != null }
            }.sortedByDescending {
                it.permissions.find { perm -> perm.name == android.Manifest.permission.RECORD_AUDIO }?.lastUsedTime
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    init {
        loadApps()
    }

    private var loadJob: Job? = null

    /**
     * Загружает список приложений.
     *
     * Исправления: повторный вызов отменяет предыдущую загрузку (иначе несколько
     * подписок на один поток), ошибка чтения пакетов больше не оставляет экран в
     * вечной загрузке, а `_isLoading` не остаётся `true` на пустом списке.
     */
    fun loadApps() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.getInstalledApps().collect { appList ->
                    _apps.value = appList
                    _isLoading.value = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _isLoading.value = false
            } finally {
                // Отменённая загрузка не должна гасить индикатор новой: иначе
                // повторный `loadApps()` сразу после отмены оставляет экран «пустым».
                if (isActive) _isLoading.value = false
            }
        }
    }

    fun setFilter(filter: PrivacyFilter) {
        _selectedFilter.value = filter
    }

    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
