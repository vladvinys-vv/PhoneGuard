package com.phoneguard.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.phoneguard.R
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.util.SmsSender
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

interface SecurityService {
    suspend fun isLostModeActive(): Flow<Boolean>
    suspend fun activateLostMode()
    suspend fun stopAlarm()
    suspend fun sendLocationToContacts()
    suspend fun getTrustedContacts(): List<String>
}

/**
 * Тревога и «режим потери» анти-кражи.
 *
 * Исправления:
 *  - `activateLostMode()` мог вызываться несколько раз (подмена SIM + ручной запуск):
 *    каждый вызов создавал новый `MediaPlayer`, старый не освобождался — тревоги
 *    наслаивались друг на друга и держали аудиофокус. Теперь уже играющая тревога
 *    переиспользуется, а `MediaPlayer.create` может вернуть `null` (нет рингтона) —
 *    это больше не приводит к падению;
 *  - вибратор приводился к `Vibrator` жёстким `as` — на части прошивок это
 *    `ClassCastException`/`NPE` прямо в обработчике тревоги;
 *  - `sendLocationToContacts()` был заглушкой: получал номер и ничего не отправлял,
 *    хотя его вызывают и при подмене SIM, и из экрана подтверждения. Теперь
 *    координаты реально уходят доверенному номеру (или сообщение о том, что
 *    местоположение недоступно).
 */
@Singleton
class AntiTheftSecurityServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val locationProvider: LocationProvider
) : SecurityService {

    private val _lostModeActive = MutableStateFlow(false)
    private var alarmPlayer: MediaPlayer? = null
    private var alarmVibrator: Vibrator? = null

    override suspend fun isLostModeActive(): Flow<Boolean> = _lostModeActive

    override suspend fun activateLostMode() {
        _lostModeActive.value = true
        startAlarm()
        preferencesManager.setRemoteAlarmEnabled(true)
    }

    @Synchronized
    private fun startAlarm() {
        if (alarmPlayer == null) {
            runCatching {
                val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val player = MediaPlayer.create(context, ringtone) ?: return@runCatching
                player.isLooping = true
                player.start()
                alarmPlayer = player
            }
        }

        if (alarmVibrator == null) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    manager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                }
                vibrator?.vibrate(longArrayOf(0, 500, 1000), 0)
                alarmVibrator = vibrator
            }
        }
    }

    override suspend fun stopAlarm() {
        runCatching { alarmPlayer?.stop() }
        runCatching { alarmPlayer?.release() }
        alarmPlayer = null
        runCatching { alarmVibrator?.cancel() }
        alarmVibrator = null
        preferencesManager.setRemoteAlarmEnabled(false)
        _lostModeActive.value = false
    }

    /**
     * Отправляет текущие координаты доверенному номеру (если он задан и есть разрешение).
     *
     * Текст локализован; координаты форматируются с `Locale.US`, иначе в немецкой
     * локали получилось бы `52,52` — ссылка на карту ломается.
     */
    override suspend fun sendLocationToContacts() {
        val recipients = getTrustedContacts()
        if (recipients.isEmpty()) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val location = runCatching { locationProvider.getLastLocation() }.getOrNull()
        val message = if (location != null) {
            val latitude = String.format(Locale.US, "%.5f", location.latitude)
            val longitude = String.format(Locale.US, "%.5f", location.longitude)
            context.getString(
                R.string.location_share_sms,
                latitude,
                longitude,
                "https://maps.google.com/?q=$latitude,$longitude"
            )
        } else {
            context.getString(R.string.location_share_unavailable)
        }

        recipients.forEach { number ->
            // Делегируем общему SmsSender: он разбивает длинный текст с координатами
            // на части и не бросает исключение, если SMS недоступны.
            SmsSender.send(context, number, message)
        }
    }

    /**
     * Доверенные номера для тревоги.
     *
     * `backupNumber` — `Flow<String?>`: настройка может быть не задана вовсе.
     * Раньше здесь стояло `backupNumber.isBlank()` и `listOf(backupNumber)`, что
     * не компилируется (`isBlank()` у `String?` и `List<String?>` вместо
     * `List<String>`) — ошибку не ловил ни один чек, потому что kotlinx.coroutines
     * отсутствует в офлайн-проверке фронтенда и тип `first()` не разрешался.
     */
    override suspend fun getTrustedContacts(): List<String> {
        val backupNumber = preferencesManager.backupNumber.first()
        return listOfNotNull(backupNumber?.takeIf { it.isNotBlank() })
    }
}
