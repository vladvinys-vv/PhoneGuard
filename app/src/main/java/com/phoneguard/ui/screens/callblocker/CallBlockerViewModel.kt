package com.phoneguard.ui.screens.callblocker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.data.local.BlockedNumber
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CallBlockerViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val appDatabase: com.phoneguard.data.local.AppDatabase
) : ViewModel() {
    private val blockedNumberDao = appDatabase.blockedNumberDao()
    private val blockedLogDao = appDatabase.blockedLogDao()

    val blacklist: StateFlow<List<BlockedNumber>> = blockedNumberDao.getBlacklist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    val whitelist: StateFlow<List<BlockedNumber>> = blockedNumberDao.getWhitelist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    val blockedLog: StateFlow<List<BlockedLog>> = blockedLogDao.getAllBlockedLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val blockUnknown: StateFlow<Boolean> = preferencesManager.blockUnknownNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val blockHidden: StateFlow<Boolean> = preferencesManager.blockHiddenNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val blockInternational: StateFlow<Boolean> = preferencesManager.blockInternationalNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    fun addNumber(number: String, name: String? = null, isWhitelist: Boolean = false) {
        viewModelScope.launch {
            blockedNumberDao.insertBlockedNumber(
                BlockedNumber(
                    phoneNumber = number,
                    name = name,
                    isWhitelist = isWhitelist,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteNumber(number: BlockedNumber) {
        viewModelScope.launch {
            blockedNumberDao.deleteBlockedNumber(number)
        }
    }

    fun toggleBlockUnknown(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setBlockUnknownNumbers(enabled)
        }
    }

    fun toggleBlockHidden(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setBlockHiddenNumbers(enabled)
        }
    }

    fun toggleBlockInternational(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setBlockInternationalNumbers(enabled)
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
