package com.phoneguard.callblocker

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class CallScreeningServiceImpl : CallScreeningService() {

    @Inject
    lateinit var appDatabase: AppDatabase

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle.schemeSpecificPart ?: return

        runBlocking(Dispatchers.IO) {
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

        if (blockedNumberDao.isWhitelisted(phoneNumber)) {
            return false
        }

        if (blockedNumberDao.isBlacklisted(phoneNumber)) {
            return true
        }

        val blockUnknown = preferencesManager.blockUnknownNumbers.first()
        if (blockUnknown && phoneNumber.isBlank()) {
            return true
        }

        val blockHidden = preferencesManager.blockHiddenNumbers.first()
        if (blockHidden && phoneNumber == "private") {
            return true
        }

        val blockInternational = preferencesManager.blockInternationalNumbers.first()
        if (blockInternational && phoneNumber.startsWith("+")) {
            return true
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
