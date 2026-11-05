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
import com.phoneguard.util.NotificationChannelInstaller
import com.phoneguard.util.NotificationChannels
import com.phoneguard.util.SmsSender
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface SecurityService {
    suspend fun isLostModeActive(): Flow<Boolean>
    suspend fun activateLostMode()

    /**
     * Только сирена: без режима потери.
     *
     * Нужна реакции на вторжение: посторонний трогает телефон — звук нужен, а состояние
     * «телефон потерян» (оно сохраняется в настройках и переживает перезапуск) — нет.
     */
    suspend fun soundAlarm()

    suspend fun stopAlarm()

    /**
     * Сирена на [durationMs] миллисекунд, без режима потери.
     *
     * Для удалённой команды `ALARM`: она должна звучать и в беззвучном режиме (поэтому
     * `USAGE_ALARM`, а не рингтон звонка), не наслаиваться на тревогу режима потери и не
     * выключать этот режим, когда отзвучит.
     */
    suspend fun soundSirenFor(durationMs: Long)

    /**
     * Возобновляет защиту после загрузки устройства или обновления приложения.
     *
     * Если режим потери сохранён в настройках, сирена включается снова: перезагрузка —
     * самый простой способ её выключить, и раньше он работал.
     */
    suspend fun resumeAfterBoot()
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

    /**
     * Состояние анти-кражи под одной блокировкой.
     *
     * Меняется из разных потоков: SMS-приёмник (команды), экран анти-кражи, обработчик подмены
     * SIM. Раньше `startAlarm()` был `@Synchronized`, а `stopAlarm()` — нет, поэтому остановка
     * могла освободить плеер, который в этот момент создавался, или оставить «залипший» флаг.
     */
    private var lostMode = LostModeState.inactive()
    private var stateRestored = false

    private var alarmPlayer: MediaPlayer? = null
    private var alarmVibrator: Vibrator? = null

    /**
     * Область для отложенного выключения сирены.
     *
     * Своя, а не область приёмника: `goAsync()` даёт около десяти секунд, а команда `ALARM`
     * звучит минуту. Если бы таймер жил в области приёмника, `pendingResult.finish()` ждал бы
     * окончания звука, и система убила бы процесс по таймауту broadcast.
     */
    private val sirenScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
    private var sirenJob: kotlinx.coroutines.Job? = null

    /**
     * Состояние режима потери.
     *
     * Подтягивает сохранённое значение при первом обращении: после перезапуска процесса
     * `MutableStateFlow` сбрасывался в `false`, хотя телефон всё ещё «потерян» (настройка
     * `remote_alarm_enabled` при этом оставалась включённой — состояние противоречило само себе).
     */
    override suspend fun isLostModeActive(): Flow<Boolean> {
        restoreStateIfNeeded()
        return _lostModeActive
    }

    override suspend fun activateLostMode() {
        // Сначала настройки: если процесс убьют сразу после старта тревоги, режим потери
        // всё равно останется включённым.
        preferencesManager.setLostModeActive(true)
        val alarmStarted = startAlarm()
        synchronized(this) {
            lostMode = LostModePolicy.onActivate(lostMode, alarmStarted)
            _lostModeActive.value = lostMode.lostModeActive
            stateRestored = true
        }
    }

    /**
     * Запускает тревогу (звук и вибрацию) и сообщает, играет ли она.
     *
     * Идемпотентна: повторный вызов переиспользует уже играющий плеер — иначе тревоги
     * наслаивались бы друг на друга и держали аудиофокус.
     */
    @Synchronized
    private fun startAlarm(): Boolean {
        if (alarmPlayer == null) {
            alarmPlayer = runCatching {
                val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                // USAGE_ALARM, а не `MediaPlayer.create`: тот играет как медиа, и в беззвучном
                // режиме (или под «Не беспокоить») тревоги просто не слышно — ровно тогда,
                // когда она нужна. Заодно поднимаем громкость канала будильника: на Android 15+
                // система может это запретить, поэтому в try/catch.
                raiseAlarmVolume()
                MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, ringtone)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
        }

        if (alarmVibrator == null) {
            alarmVibrator = runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    manager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                }
                vibrator?.vibrate(longArrayOf(0, 500, 1000), 0)
                vibrator
            }.getOrNull()
        }

        return alarmPlayer != null
    }

    /**
     * Максимальная громкость канала будильника.
     *
     * На Android 15+ стороннему приложению менять громкость запрещено (`SecurityException`) —
     * звук всё равно играет, поэтому исключение здесь не имеет значения.
     */
    private fun raiseAlarmVolume() {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0)
        }
    }

    override suspend fun soundAlarm() {
        restoreStateIfNeeded()
        val alarmStarted = startAlarm()
        if (!alarmStarted) return
        synchronized(this) {
            lostMode = LostModePolicy.onIntrusionAlarm(lostMode)
            _lostModeActive.value = lostMode.lostModeActive
        }
    }

    override suspend fun soundSirenFor(durationMs: Long) {
        if (durationMs <= 0L) return
        val alarmStarted = startAlarm()
        if (!alarmStarted) return
        synchronized(this) {
            lostMode = LostModePolicy.onIntrusionAlarm(lostMode)
            _lostModeActive.value = lostMode.lostModeActive
        }
        sirenJob?.cancel()
        sirenJob = sirenScope.launch {
            kotlinx.coroutines.delay(durationMs)
            releaseAlarm()
            // Сирена отзвучала сама — временное предупреждение больше не актуально.
            cancelTransientNotifications(NotificationChannels.StopReason.SIREN_EXPIRED)
            synchronized(this@AntiTheftSecurityServiceImpl) {
                lostMode = LostModePolicy.onSirenSilenced(lostMode)
                _lostModeActive.value = lostMode.lostModeActive
            }
        }
    }

    override suspend fun resumeAfterBoot() {
        restoreStateIfNeeded()
        val saved = preferencesManager.lostModeActive.first()
        val alarmStarted = if (saved) startAlarm() else false
        synchronized(this) {
            lostMode = LostModePolicy.onDeviceBoot(saved, alarmStarted)
            _lostModeActive.value = lostMode.lostModeActive
            stateRestored = true
        }
    }

    override suspend fun stopAlarm() {
        releaseAlarm()
        // Раньше сирена замолкала, а уведомление о вторжении оставалось висеть до тапа:
        // выглядело так, будто событие продолжается.
        cancelTransientNotifications(NotificationChannels.StopReason.ALARM_STOPPED)
        synchronized(this) {
            lostMode = LostModePolicy.onStopAlarm(lostMode)
            _lostModeActive.value = lostMode.lostModeActive
            stateRestored = true
        }
        preferencesManager.setLostModeActive(false)
    }

    /**
     * Гасит временные уведомления, относящиеся к остановленному событию.
     *
     * Список id — в чистой политике [NotificationChannels.cancelledIds], чтобы «что гасить при
     * остановке тревоги» не расползалось по сервисам.
     */
    private fun cancelTransientNotifications(reason: NotificationChannels.StopReason) {
        runCatching {
            NotificationChannelInstaller.cancel(context, NotificationChannels.cancelledIds(reason))
        }
    }

    /** Останавливает и освобождает плеер и вибрацию; состояние меняется отдельно, под блокировкой. */
    @Synchronized
    private fun releaseAlarm() {
        runCatching { alarmPlayer?.stop() }
        runCatching { alarmPlayer?.release() }
        alarmPlayer = null
        runCatching { alarmVibrator?.cancel() }
        alarmVibrator = null
    }

    /**
     * Подтягивает сохранённый режим потери при первом обращении к состоянию.
     *
     * Тревога при этом **не** включается: плеера после перезапуска нет, а «тихий» старт
     * приложения не должен включать сирену (правило — в [LostModePolicy.onRestore]).
     */
    private suspend fun restoreStateIfNeeded() {
        if (stateRestored) return
        val saved = runCatching { preferencesManager.lostModeActive.first() }.getOrDefault(false)
        synchronized(this) {
            if (stateRestored) return
            lostMode = LostModePolicy.onRestore(saved)
            _lostModeActive.value = lostMode.lostModeActive
            stateRestored = true
        }
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
