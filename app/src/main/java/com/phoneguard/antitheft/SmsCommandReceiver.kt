package com.phoneguard.antitheft

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsCommandReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Формат команды: #PG:PIN:COMMAND (PG = PhoneGuard)
    // Пример: #PG:1234:LOCK
    private val commandPattern = Regex("""#PG:(\d+):(\w+)""", RegexOption.IGNORE_CASE)

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            smsMessages.forEach { message ->
                val sender = message.originatingAddress ?: return@forEach
                val messageBody = message.displayMessageBody ?: return@forEach
                scope.launch {
                    processCommand(context, messageBody, sender)
                }
            }
        }
    }

    private suspend fun processCommand(context: Context, command: String, sender: String) {
        val match = commandPattern.find(command) ?: return
        val pin = match.groupValues[1]
        val cmd = match.groupValues[2].uppercase()

        val storedPin = preferencesManager.pinCode.first()
        if (storedPin == null || pin != storedPin) {
            // Неверный PIN — логируем попытку
            return
        }

        when (cmd) {
            "LOCK" -> lockDevice(context, sender)
            "ALARM" -> triggerAlarm(context)
            "LOCATION" -> sendLocation(context, sender)
            "WIPE" -> wipeDevice(context)
            "PHOTO" -> takeStealthPhoto(context, sender)
            "STATUS" -> sendDeviceStatus(context, sender)
        }
    }

    private fun lockDevice(context: Context, sender: String) {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiverImpl::class.java)

        if (devicePolicyManager.isAdminActive(componentName)) {
            devicePolicyManager.lockNow()
            sendSms(context, sender, "PhoneGuard: Устройство заблокировано.")
        } else {
            sendSms(context, sender, "PhoneGuard: Device Admin не активен. Блокировка невозможна.")
        }
    }

    private fun triggerAlarm(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val notification: Ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        notification.play()

        // Stop after 60 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            notification.stop()
        }, 60000)
    }

    private fun sendLocation(context: Context, sender: String) {
        scope.launch {
            val locationDetector = com.phoneguard.antitheft.LocationProvider(context)
            val location = locationDetector.getLastLocation()
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                val accuracy = location.accuracy
                val mapsUrl = "https://maps.google.com/?q=$lat,$lon"
                sendSms(context, sender, "PhoneGuard: Местоположение — $lat, $lon (точность: ${accuracy.toInt()}м). $mapsUrl")
            } else {
                sendSms(context, sender, "PhoneGuard: Не удалось определить местоположение.")
            }
        }
    }

    private fun wipeDevice(context: Context) {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiverImpl::class.java)

        if (devicePolicyManager.isAdminActive(componentName)) {
            // Отправляем SMS перед вайпом
            val backupNumber = preferencesManager.backupNumber
            // wipeData запускается асинхронно — система перезагрузится
            devicePolicyManager.wipeData(0)
        }
    }

    private fun takeStealthPhoto(context: Context, sender: String) {
        // Запуск сервиса для фото с задней камеры
        val intent = Intent(context, StealthCameraService::class.java).apply {
            action = StealthCameraService.ACTION_TAKE_PHOTO
            putExtra(StealthCameraService.EXTRA_SENDER, sender)
        }
        context.startForegroundService(intent)
    }

    private fun sendDeviceStatus(context: Context, sender: String) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val simState = when (telephonyManager.simState) {
            android.telephony.TelephonyManager.SIM_STATE_READY -> "SIM активна"
            android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "SIM отсутствует"
            android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN требуется"
            else -> "Неизвестно"
        }
        val statusMsg = "PhoneGuard: $simState. IMEI: ${telephonyManager.imei ?: "N/A"}."
        sendSms(context, sender, statusMsg)
    }

    private fun sendSms(context: Context, destination: String, text: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(text)
            if (parts.size == 1) {
                smsManager.sendTextMessage(destination, null, text, null, null)
            } else {
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
            }
        } catch (e: Exception) {
            // SMS отправка может быть ограничена на Android 10+
        }
    }
}
