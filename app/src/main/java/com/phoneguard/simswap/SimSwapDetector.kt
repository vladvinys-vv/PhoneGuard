package com.phoneguard.simswap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.phoneguard.R
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.SimSwapRepository
import com.phoneguard.model.SimSwapEvent
import com.phoneguard.util.SecurityUtils
import com.phoneguard.util.SmsSender
import com.phoneguard.util.awaitLastLocationOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class SimSwapDetector(
    @ApplicationContext private val context: Context,
    private val repository: SimSwapRepository,
    private val preferencesManager: PreferencesManager,
    private val securityService: SecurityService,
    private val scope: CoroutineScope = CoroutineScope(IO)
) {
    private val _uiEvents = MutableSharedFlow<SimSwapUiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<SimSwapUiEvent> = _uiEvents

    /** Запускает проверку SIM «в фоне» — для мест, где нельзя ждать (ViewModel, сервисы). */
    fun onSimStateChanged() {
        scope.launch { checkSimState() }
    }

    /**
     * Проверяет текущую SIM относительно сохранённого отпечатка.
     *
     * `suspend`-версия нужна приёмнику [SimSwapReceiver]: он обязан дождаться окончания
     * работы перед `PendingResult.finish()` из `goAsync()`, иначе процесс может быть убит
     * и сохранение отпечатка потеряется.
     */
    @SuppressLint("MissingPermission")
    suspend fun checkSimState() {
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val newImsi = currentSimIdentity(telephonyManager)
        val storedIdentity = getLastKnownImsi()
        val oldImsi = storedIdentity?.removePrefix(HASH_PREFIX)

        when (SimIdentityLogic.decide(storedIdentity, newImsi)) {
            // SIM прочитать не удалось — сохранённый отпечаток не затираем.
            SimIdentityLogic.Decision.IGNORE -> return

            // Первый запуск или миграция старого (незахешированного) значения:
            // запоминаем текущую SIM. Раньше `lastKnownImsi` записывался только при
            // уже случившейся подмене, поэтому на свежей установке детектор молчал.
            SimIdentityLogic.Decision.SEED_BASELINE -> {
                saveLastKnownImsi(newImsi)
                return
            }

            SimIdentityLogic.Decision.SWAPPED -> Unit
        }

        // В ветке SWAPPED оба значения непустые — страхуемся от гонки и выходим молча.
        val currentImsi = newImsi ?: return
        val previousImsi = oldImsi ?: return

        val location = resolveLocation()
        val event = SimSwapEvent(
            timestamp = System.currentTimeMillis(),
            oldImsi = previousImsi,
            newImsi = currentImsi,
            latitude = location.first,
            longitude = location.second,
            addressString = location.third,
            isConfirmed = false,
            isAttackSuspected = false
        )
        repository.record(event)
        saveLastKnownImsi(currentImsi)
        withContext(IO) {
            preferencesManager.setSimSwapUnconfirmed(true)
        }
        _uiEvents.emit(SimSwapUiEvent.ShowConfirmation(event))
        // `uiEvents` — горячий поток без replay: если приложение закрыто (обычный случай
        // для подмены SIM), показать подтверждение некому. Уведомление открывает
        // SimSwapConfirmationActivity и работает в фоне.
        SimSwapNotifications.notifyUnconfirmedSwap(context)
        sendBackupSms(event)
    }

    suspend fun confirmCurrentSimSwap() {
        val latest = repository.latest()
        latest?.let { repository.confirm(it.id) }
        preferencesManager.setSimSwapConfirmed(true)
        preferencesManager.setSimSwapUnconfirmed(false)
        disableSensitiveAppsGuard()
        SimSwapNotifications.cancel(context)
    }

    suspend fun rejectCurrentSimSwap() {
        val latest = repository.latest()
        latest?.let {
            repository.confirm(it.id)
            repository.markAttackSuspected(it.id)
        }
        preferencesManager.setSimSwapConfirmed(true)
        preferencesManager.setSimSwapUnconfirmed(false)
        SimSwapNotifications.cancel(context)
        activateLostMode()
    }

    private suspend fun activateLostMode() {
        securityService.activateLostMode()
        securityService.sendLocationToContacts()
    }

    private suspend fun sendBackupSms(event: SimSwapEvent): Boolean = withContext(IO) {
        val backupNumber = preferencesManager.backupNumber.first()
        if (backupNumber.isNullOrBlank()) return@withContext false

        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(event.timestamp))
        val message = context.getString(
            R.string.sim_swap_sms_message,
            date,
            event.addressString ?: context.getString(R.string.unknown_location)
        )

        // Раньше здесь был свой выбор SmsManager и вызов без разбиения на части.
        // Отправка на резервный номер — единственный канал оповещения при подмене
        // SIM, поэтому она идёт через общий SmsSender без исключений в фоне.
        // `return` внутри lambda `withContext` запрещён — метка обязательна.
        return@withContext SmsSender.send(context, backupNumber, message)
    }

    /**
     * Идентификатор текущей SIM.
     *
     * Начиная с Android 10 `subscriberId` (IMSI) и `simSerialNumber` (ICCID)
     * возвращают `null` обычным приложениям, поэтому раньше детектор молча
     * переставал работать. Здесь используется первый доступный признак:
     * IMSI → ICCID → пара «оператор + страна», а результат хешируется, чтобы в
     * SharedPreferences не хранился IMSI в открытом виде.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun currentSimIdentity(telephonyManager: TelephonyManager): String? {
        val raw = runCatching { telephonyManager.subscriberId }.getOrNull()
            ?: runCatching { telephonyManager.simSerialNumber }.getOrNull()
            ?: runCatching {
                listOfNotNull(telephonyManager.simOperator, telephonyManager.simCountryIso)
                    .joinToString("|")
                    .ifBlank { null }
            }.getOrNull()
            ?: return null

        return SecurityUtils.sha256Hex(raw.toByteArray(Charsets.UTF_8)) ?: raw
    }

    suspend fun getLastUnconfirmedEvent(): SimSwapEvent? = withContext(IO) {
        repository.latestUnconfirmed()
    }

    suspend fun hasUnconfirmed(): Boolean = preferencesManager.isSimSwapUnconfirmed.first()

    private suspend fun getLastKnownImsi(): String? = withContext(IO) {
        preferencesManager.lastKnownImsi.first().ifEmpty { null }
    }

    private suspend fun saveLastKnownImsi(imsi: String?) = withContext(IO) {
        val value = imsi?.let { if (it.startsWith(HASH_PREFIX)) it else HASH_PREFIX + it }
        preferencesManager.setLastKnownImsi(value.orEmpty())
    }

    private suspend fun resolveLocation(): Triple<Double?, Double?, String?> = withContext(IO) {
        try {
            val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
            // Ожидание ограничено по времени: `Task.await()` без таймаута мог висеть
            // вечно, а вместе с ним — вся реакция на подмену SIM.
            val location = fused.awaitLastLocationOrNull() ?: return@withContext Triple(null, null, null)
            val geocoder = Geocoder(context, Locale.getDefault())
            // Geocoder.getFromLocation() помечен в SDK как возвращающий null (нет сервиса
            // геокодирования, лимит запросов): без `?: emptyList()` это NPE, который раньше
            // молча съедал внешний catch и оставлял координаты без адреса.
            val addresses: List<Address> =
                geocoder.getFromLocation(location.latitude, location.longitude, 1) ?: emptyList()
            val address = addresses.firstOrNull()?.getAddressLine(0)
            Triple(location.latitude, location.longitude, address)
        } catch (e: Exception) {
            Triple(null, null, null)
        }
    }

    suspend fun enableSensitiveAppsGuard() {
        if (!preferencesManager.isSensitiveAppsGuardEnabled.first()) return
        // Guard will be handled via accessibility observer in existing app lock flows
    }

    private fun disableSensitiveAppsGuard() {
        // noop post confirm
    }

    companion object {
        const val ACTION_STOP_CONFIRMATION = "com.phoneguard.simswap.ACTION_STOP_CONFIRMATION"
    }
}

sealed interface SimSwapUiEvent {
    data class ShowConfirmation(val event: SimSwapEvent) : SimSwapUiEvent
}

/**
 * Решение по сравнению отпечатков SIM. Вынесено из [SimSwapDetector], чтобы
 * покрывать его JVM-тестами: сам детектор требует `TelephonyManager` и разрешений.
 */
internal object SimIdentityLogic {

    enum class Decision {
        /** Сравнивать не с чем: идентификатор SIM недоступен. */
        IGNORE,

        /** Нет базового отпечатка (первый запуск) или он в старом формате — сохранить текущий. */
        SEED_BASELINE,

        /** Отпечаток изменился — это подмена SIM. */
        SWAPPED
    }

    fun decide(storedIdentity: String?, currentIdentity: String?): Decision {
        if (currentIdentity == null) return Decision.IGNORE
        if (storedIdentity == null) return Decision.SEED_BASELINE
        if (!storedIdentity.startsWith(HASH_PREFIX)) return Decision.SEED_BASELINE
        val stored = storedIdentity.removePrefix(HASH_PREFIX)
        return if (stored == currentIdentity) Decision.IGNORE else Decision.SWAPPED
    }
}

private const val HASH_PREFIX = "sha256:"
