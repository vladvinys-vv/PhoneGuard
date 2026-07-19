package com.phoneguard.ui.screens.callblocker

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.data.local.BlockedNumber
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.util.CsvImportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CallBlockerViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val appDatabase: com.phoneguard.data.local.AppDatabase,
    @ApplicationContext private val context: Context,
    private val csvImportHelper: CsvImportHelper
) : ViewModel() {
    private val blockedNumberDao = appDatabase.blockedNumberDao()
    private val blockedLogDao = appDatabase.blockedLogDao()

    private val _blacklistPage = MutableStateFlow<List<BlockedNumber>>(emptyList())
    val blacklist: StateFlow<List<BlockedNumber>> = _blacklistPage.asStateFlow()

    private val _whitelistPage = MutableStateFlow<List<BlockedNumber>>(emptyList())
    val whitelist: StateFlow<List<BlockedNumber>> = _whitelistPage.asStateFlow()

    private val _blockedLogPage = MutableStateFlow<List<BlockedLog>>(emptyList())
    val blockedLog: StateFlow<List<BlockedLog>> = _blockedLogPage.asStateFlow()

    private val _hasMoreBlacklist = MutableStateFlow(false)
    val hasMoreBlacklist: StateFlow<Boolean> = _hasMoreBlacklist.asStateFlow()

    private val _hasMoreWhitelist = MutableStateFlow(false)
    val hasMoreWhitelist: StateFlow<Boolean> = _hasMoreWhitelist.asStateFlow()

    private val _hasMoreLogs = MutableStateFlow(false)
    val hasMoreLogs: StateFlow<Boolean> = _hasMoreLogs.asStateFlow()

    private val pageSize = 20
    private val blacklistOffset = MutableStateFlow(0)
    private val whitelistOffset = MutableStateFlow(0)
    private val logOffset = MutableStateFlow(0)

    val blockUnknown: StateFlow<Boolean> = preferencesManager.blockUnknownNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val blockHidden: StateFlow<Boolean> = preferencesManager.blockHiddenNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val blockInternational: StateFlow<Boolean> = preferencesManager.blockInternationalNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    init {
        loadBlacklist()
        loadWhitelist()
        loadBlockedLogs()
    }

    fun loadBlacklist() {
        viewModelScope.launch {
            val items = blockedNumberDao.getPagedBlockedNumbers(pageSize, blacklistOffset.value)
            val total = blockedNumberDao.getBlockedNumbersCount()
            _blacklistPage.value = items
            _hasMoreBlacklist.value = (blacklistOffset.value + items.size) < total
        }
    }

    fun loadWhitelist() {
        viewModelScope.launch {
            val items = blockedNumberDao.getPagedBlockedNumbers(pageSize, whitelistOffset.value)
            val total = blockedNumberDao.getBlockedNumbersCount()
            _whitelistPage.value = items
            _hasMoreWhitelist.value = (whitelistOffset.value + items.size) < total
        }
    }

    fun loadBlockedLogs() {
        viewModelScope.launch {
            val items = blockedLogDao.getPagedBlockedLogs(pageSize, logOffset.value)
            val total = blockedLogDao.getBlockedLogsCount()
            _blockedLogPage.value = items
            _hasMoreLogs.value = (logOffset.value + items.size) < total
        }
    }

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
            if (isWhitelist) {
                whitelistOffset.value = 0
                loadWhitelist()
            } else {
                blacklistOffset.value = 0
                loadBlacklist()
            }
        }
    }

    fun deleteNumber(number: BlockedNumber) {
        viewModelScope.launch {
            blockedNumberDao.deleteBlockedNumber(number)
            if (number.isWhitelist) {
                whitelistOffset.value = 0
                loadWhitelist()
            } else {
                blacklistOffset.value = 0
                loadBlacklist()
            }
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

    fun importFromCsv(uri: Uri, isWhitelist: Boolean = false) {
        viewModelScope.launch {
            val numbers = csvImportHelper.importBlockedNumbers(uri, isWhitelist)
            numbers.forEach { number ->
                addNumber(number, null, isWhitelist)
            }
        }
    }
}