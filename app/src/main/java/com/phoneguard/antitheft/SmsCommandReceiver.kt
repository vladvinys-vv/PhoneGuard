package com.phoneguard.antitheft

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phoneguard.R
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.util.SmsSender
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Обработка удалённых SMS-команд вида `#PG:PIN:COMMAND`.
 *
 * Исправлено относительно прежней версии:
 *  - `preferencesManager.backupNumber` (Flow) читался как значение и никуда не
 *    использовался, поэтому «прощальное» SMS перед вайпом не отправлялось;
 *  - `telephonyManager.imei` на Android 10+ бросает SecurityException без
 *    READ_PHONE_STATE (а для обычных приложений недоступен вовсе) — вместо
 *    него используется `Settings.Secure.ANDROID_ID`;
 *  - многочастные SMS обрабатывались по отдельности, поэтому одна команда
 *    могла выполниться несколько раз; теперь части собираются в одно сообщение;
 *  - `startForegroundService` из фона на Android 12+ бросает исключение —
 *    вызов обёрнут в try/catch;
 *  - процесс больше не убивается сразу после `onReceive` благодаря `goAsync()`.
 */
@AndroidEntryPoint
class SmsCommandReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var locationProvider: LocationProvider

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.firstNotNullOfOrNull { it.originatingAddress } ?: return
        // Многочастное SMS приходит как несколько SmsMessage в одном интенте.
        val body = messages.mapNotNull { it.displayMessageBody }.joinToString("")
        if (!SmsRemoteCommand.containsCommand(body)) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                processCommand(context, body, sender)
            } catch (e: Exception) {
                Log.w(TAG, "SMS command failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processCommand(context: Context, command: String, sender: String) {
        val parsed = SmsRemoteCommand.parse(command) ?: return

        // PIN проверяется до любой реакции: без него команда игнорируется.
        if (!preferencesManager.verifyPin(parsed.pin)) {
            Log.w(TAG, "SMS command rejected: wrong PIN")
            // Подбор PIN по SMS больше не бесконечен (см. PinAttemptLimiter), но
            // пользователь должен узнать о попытках: показываем локальное уведомление.
            notifyRejectedCommand(context)
            return
        }

        // Неизвестная команда (action == null) не выполняется, но PIN уже проверен —
        // так попытка подбора остаётся видимой для пользователя.
        when (parsed.action) {
            SmsRemoteCommand.Action.LOCK -> lockDevice(context, sender)
            SmsRemoteCommand.Action.ALARM -> triggerAlarm(context)
            SmsRemoteCommand.Action.LOCATION -> sendLocation(context, sender)
            SmsRemoteCommand.Action.WIPE -> wipeDevice(context, sender)
            SmsRemoteCommand.Action.PHOTO -> takeStealthPhoto(context, sender)
            SmsRemoteCommand.Action.STATUS -> sendDeviceStatus(context, sender)
            null -> Unit
        }
    }

    private fun lockDevice(context: Context, sender: String) {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiverImpl::class.java)

        if (devicePolicyManager.isAdminActive(componentName)) {
            devicePolicyManager.lockNow()
            sendSms(context, sender, context.getString(R.string.sms_reply_device_locked))
        } else {
            sendSms(context, sender, context.getString(R.string.sms_reply_lock_unavailable))
        }
    }

    private fun triggerAlarm(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            val ringtone: Ringtone = RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ) ?: return
            ringtone.play()

            Handler(Looper.getMainLooper()).postDelayed({ ringtone.stop() }, ALARM_DURATION_MS)
        } catch (e: SecurityException) {
            // На Android 15+ изменение громкости сторонним приложением запрещено —
            // сигнал всё равно проигрываем.
            Log.w(TAG, "Cannot raise alarm volume", e)
        }
    }

    private suspend fun sendLocation(context: Context, sender: String) {
        val location = locationProvider.getLastLocation()
        if (location != null) {
            // Locale.US: в немецкой локали координаты получились бы «52,52», и ссылка
            // на карту ломается.
            val lat = String.format(java.util.Locale.US, "%.5f", location.latitude)
            val lon = String.format(java.util.Locale.US, "%.5f", location.longitude)
            sendSms(
                context,
                sender,
                context.getString(
                    R.string.location_share_sms,
                    lat,
                    lon,
                    "https://maps.google.com/?q=$lat,$lon"
                )
            )
        } else {
            sendSms(context, sender, context.getString(R.string.location_share_unavailable))
        }
    }

    private suspend fun wipeDevice(context: Context, sender: String) {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiverImpl::class.java)

        if (!devicePolicyManager.isAdminActive(componentName)) {
            Log.w(TAG, "Wipe requested but Device Admin is not active")
            return
        }

        // Отправляем подтверждение на резервный номер (если он задан) до вайпа:
        // после wipeData устройство уже не сможет отправить SMS.
        val backupNumber = preferencesManager.backupNumber.first()
        if (!backupNumber.isNullOrBlank()) {
            sendSms(context, backupNumber, context.getString(R.string.sms_reply_wipe_pending))
        }

        Log.w(TAG, "Executing remote wipe (API level ${android.os.Build.VERSION.SDK_INT})")
        try {
            @Suppress("DEPRECATION")
            devicePolicyManager.wipeData(0)
        } catch (e: Exception) {
            // wipeData доступен только device owner; для обычного Device Admin это
            // SecurityException. Раньше исключение молча проглатывалось внешним
            // catch, и владелец был уверен, что данные стёрты.
            Log.w(TAG, "Remote wipe failed", e)
            sendSms(context, sender, context.getString(R.string.wipe_failed_sms))
        }
    }

    private fun takeStealthPhoto(context: Context, sender: String) {
        val intent = Intent(context, StealthCameraService::class.java).apply {
            action = StealthCameraService.ACTION_TAKE_PHOTO
            putExtra(StealthCameraService.EXTRA_SENDER, sender)
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            // Android 12+: запуск foreground-сервиса из фона запрещён без исключения.
            Log.w(TAG, "Cannot start stealth camera service", e)
            sendSms(context, sender, context.getString(R.string.sms_reply_photo_failed))
        }
    }

    private fun sendDeviceStatus(context: Context, sender: String) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        // На Android 10+ simState может потребовать READ_PHONE_STATE.
        val stateValue = runCatching { telephonyManager.simState }
            .getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
        // Ответ уходит на телефон владельца — он тоже должен быть на языке устройства.
        val simState = when (stateValue) {
            TelephonyManager.SIM_STATE_READY -> context.getString(R.string.sim_state_ready)
            TelephonyManager.SIM_STATE_ABSENT -> context.getString(R.string.sim_state_absent)
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> context.getString(R.string.sim_state_pin_required)
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> context.getString(R.string.sim_state_puk_required)
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> context.getString(R.string.sim_state_network_locked)
            else -> context.getString(R.string.sim_state_unknown)
        }
        val deviceId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "N/A"
        val battery = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1

        val statusMsg = buildString {
            append(context.getString(R.string.sms_status_line, simState, deviceId))
            if (battery >= 0) append(context.getString(R.string.sms_status_battery, battery))
        }
        sendSms(context, sender, statusMsg)
    }


    /**
     * Локальное уведомление о попытке управлять телефоном с неверным PIN.
     *
     * Ничего не отправляется наружу: владелец узнаёт о подборе только на самом
     * устройстве (канал `sms_command_channel`, `IMPORTANCE_HIGH`).
     */
    private fun notifyRejectedCommand(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        ALERT_CHANNEL_ID,
                        context.getString(R.string.sms_command_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.sms_command_rejected_title))
                .setContentText(context.getString(R.string.sms_command_rejected_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Failed to show PIN attempt notification", it) }
    }

    private fun sendSms(context: Context, destination: String, text: String) {
        // Раньше здесь была своя копия логики; теперь она одна — в [SmsSender],
        // чтобы ответы на команды и тревоги уходили одинаково.
        SmsSender.send(context, destination, text)
    }

    companion object {
        private const val TAG = "SmsCommandReceiver"
        private const val ALARM_DURATION_MS = 60_000L

        /** Канал уведомлений о попытках подбора PIN по SMS. */
        private const val ALERT_CHANNEL_ID = "sms_command_channel"
        private const val ALERT_NOTIFICATION_ID = 8101
    }
}
