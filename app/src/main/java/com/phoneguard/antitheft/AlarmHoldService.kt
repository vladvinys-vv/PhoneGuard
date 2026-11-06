package com.phoneguard.antitheft

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phoneguard.R
import com.phoneguard.ui.MainActivity
import com.phoneguard.util.NotificationChannelInstaller
import com.phoneguard.util.NotificationChannels
import com.phoneguard.util.NotificationIds
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Удерживает процесс живым, пока звучит сирена.
 *
 * До этого сирена жила просто в памяти приложения: `MediaPlayer` в
 * [AntiTheftSecurityServiceImpl] и всё. Для системы это обычный фоновый процесс, а значит
 * первый же кандидат на убийство при нехватке памяти — тревога обрывалась на середине, и
 * телефон замолкал. Ситуация тем хуже, чем она нужнее: сигнал включают как раз тогда, когда
 * приложение давно в фоне (SMS-команда владельца, режим потери после загрузки).
 *
 * Сервис намеренно **не владеет** плеером: звук остаётся в [AntiTheftSecurityServiceImpl],
 * чтобы не переписывать всю логику тревоги и не плодить второй источник состояния. Здесь
 * только foreground-уведомление (без него система завершает сервис) и восстановление
 * тревоги, если процесс всё-таки убили.
 */
@AndroidEntryPoint
class AlarmHoldService : Service() {

    @Inject
    lateinit var securityService: SecurityService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val started = runCatching { startForeground(NotificationIds.ALARM_HOLD, buildNotification()) }
        if (started.isFailure) {
            // Без foreground-уведомления сервис живёт секунды — и держать процесс незачем.
            Log.w(TAG, "Cannot enter foreground: alarm will not be held alive", started.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }

        // `START_STICKY` перезапускает сервис с `null`-интентом после убийства процесса.
        // Плеера в новом процессе нет, поэтому тревогу нужно поднять заново по сохранённому
        // состоянию — иначе останется только уведомление «сирена звучит» без звука.
        if (AlarmHoldPolicy.shouldRestoreAlarm(intent == null, intent?.action, ACTION_RESTORE)) {
            restoreAlarmIfLost()
        }
        return START_STICKY
    }

    private fun restoreAlarmIfLost() {
        serviceScope.launch {
            runCatching {
                securityService.resumeAfterBoot()
                if (!securityService.isLostModeActive().first()) {
                    // Режим потери выключили, пока процесса не было — держать нечего.
                    stopSelf()
                }
            }.onFailure { Log.w(TAG, "Cannot restore alarm after process restart", it) }
        }
    }

    private fun ensureChannel() {
        NotificationChannelInstaller.install(
            this,
            NotificationChannels.Spec.INTRUSION_WARNING,
            getString(R.string.intrusion_warning_channel_name),
            getString(R.string.intrusion_warning_channel_description)
        )
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NotificationIds.ALARM_HOLD,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NotificationChannels.Spec.INTRUSION_WARNING.channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.alarm_hold_title))
            .setContentText(getString(R.string.alarm_hold_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmHoldService"

        /** Явный запуск: сирена начала звучать. */
        const val ACTION_START = "com.phoneguard.antitheft.ACTION_ALARM_HOLD_START"

        /** Восстановление после перезапуска процесса. */
        const val ACTION_RESTORE = "com.phoneguard.antitheft.ACTION_ALARM_HOLD_RESTORE"

        /**
         * Запускает удержание процесса.
         *
         * @return `false`, если система запретила foreground-сервис (Android 12+, приложение
         *   не device admin и не на экране). Сирена при этом всё равно играет — просто
         *   система может её убить; вызывающая сторона решает, что с этим делать
         *   (`BackgroundStartPolicy.fallback`).
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, AlarmHoldService::class.java).apply {
                action = ACTION_START
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Alarm hold service start rejected by system", e)
                false
            }
        }

        /** Снимает удержание: сирена остановлена. */
        fun stop(context: Context) {
            // `stopService`, а не `startService` с командой: из фона `startService` бросает
            // IllegalStateException, и сервис продолжал бы держать процесс.
            runCatching { context.stopService(Intent(context, AlarmHoldService::class.java)) }
        }
    }
}
