package com.phoneguard.callblocker

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class CallScreeningServiceImpl : CallScreeningService() {

    @Inject
    lateinit var appDatabase: AppDatabase

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle.schemeSpecificPart ?: return
        
        runBlocking {
            val shouldBlock = checkShouldBlockNumber(phoneNumber)
            
            val response = if (shouldBlock) {
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(true)
                    .setSkipNotification(true)
                    .build()
            } else {
                CallResponse.Builder()
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .build()
            }
            
            respondToCall(callDetails, response)
            
            if (shouldBlock) {
                logBlockedCall(phoneNumber)
            }
        }
    }

    private suspend fun checkShouldBlockNumber(phoneNumber: String): Boolean {
        val blockedNumberDao = appDatabase.blockedNumberDao()
        
        // Check whitelist first - always allow whitelisted numbers
        if (blockedNumberDao.isWhitelisted(phoneNumber)) {
            return false
        }
        
        // Check blacklist
        if (blockedNumberDao.isBlacklisted(phoneNumber)) {
            return true
        }
        
        // Check block rules
        preferencesManager.blockUnknownNumbers.collect { blockUnknown ->
            if (blockUnknown && phoneNumber.isBlank()) {
                return true
            }
        }
        
        preferencesManager.blockHiddenNumbers.collect { blockHidden ->
            if (blockHidden && phoneNumber == "private") {
                return true
            }
        }
        
        preferencesManager.blockInternationalNumbers.collect { blockInternational ->
            if (blockInternational && phoneNumber.startsWith("+")) {
                return true
            }
        }
        
        return false
    }

    private suspend fun logBlockedCall(phoneNumber: String) {
        val blockedLogDao = appDatabase.blockedLogDao()
        blockedLogDao.insertBlockedLog(
            com.phoneguard.data.local.BlockedLog(
                phoneNumber = phoneNumber,
                isSms = false
            )
        )
    }
}
