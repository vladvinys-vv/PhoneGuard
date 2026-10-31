package com.phoneguard.intrusion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phoneguard.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class IntrusionResponseService : Service() {
    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        private const val CHANNEL_ID = "intrusion_response_channel"
        private const val NOTIFICATION_ID = 7001

        fun start(context: Context, eventId: Long) {
            val intent = Intent(context, IntrusionResponseService::class.java).apply {
                putExtra(EXTRA_EVENT_ID, eventId)
            }
            context.startForegroundService(intent)
        }
    }

    @Inject
    lateinit var intrusionEventDao: IntrusionEventDao

    @Inject
    lateinit var intrusionResponseOrchestrator: IntrusionResponseOrchestrator

    @Inject
    lateinit var intrusionResponseExecutor: IntrusionResponseExecutor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        val eventId = intent?.getLongExtra(EXTRA_EVENT_ID, -1L) ?: -1L
        serviceScope.launch {
            try {
                val eventEntity = if (eventId > 0) intrusionEventDao.getById(eventId) else null
                val event = eventEntity?.toIntrusionEvent() ?: IntrusionEvent(
                    type = IntrusionType.UNKNOWN,
                    severity = IntrusionSeverity.HIGH
                )
                val actions = intrusionResponseOrchestrator.plan(event)
                intrusionResponseExecutor.execute(event, actions)
                if (eventId > 0) intrusionEventDao.markHandled(eventId)
            } catch (_: Exception) {
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Intrusion Response",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneGuard")
            .setContentText("Обработка угрозы")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }
}
