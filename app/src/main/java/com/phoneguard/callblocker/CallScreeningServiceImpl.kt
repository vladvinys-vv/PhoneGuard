package com.phoneguard.callblocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import androidx.annotation.RequiresApi
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.ui.MainActivity
import com.phoneguard.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class CallScreeningServiceImpl : CallScreeningService() {

    @Inject
    lateinit var appDatabase: AppDatabase

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "call_blocker_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Blocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for blocked calls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showBlockedNotification(phoneNumber: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call blocked")
            .setContentText("Blocked: $phoneNumber")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle.schemeSpecificPart ?: return

        serviceScope.launch {
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
        showBlockedNotification(phoneNumber)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
