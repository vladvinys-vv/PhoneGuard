package com.phoneguard.ui.screens.privacyscanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.repository.PrivacyScannerRepository
import com.phoneguard.model.PrivacyApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
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

    fun loadApps() {
        viewModelScope.launch {
            repository.getInstalledApps().collect { appList ->
                _apps.value = appList
                _isLoading.value = appList.isEmpty()
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
