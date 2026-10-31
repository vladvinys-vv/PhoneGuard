package com.phoneguard.callblocker

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.phoneguard.R
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.SpamNumberRepository
import com.phoneguard.util.AnalyticsHelper
import com.phoneguard.util.PhoneNumberNormalizer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Скрининг входящих звонков.
 *
 * Порядок принятия решения:
 *  1. белый список — всегда пропуск;
 *  2. чёрный список / спам-база (сравнение по нормализованному E.164);
 *  3. скрытый номер (`handle == null`) — по настройке «блокировать скрытые»;
 *  4. «неизвестный» номер (не определяется) — по настройке «блокировать неизвестные»;
 *  5. международный номер — по настройке «блокировать международные».
 *
 * Раньше `callDetails.handle` разыменовывался напрямую — на скрытых номерах это
 * приводило к NPE внутри сервиса, а проверки «неизвестный/скрытый» были
 * недостижимы, потому что выполнялись после `?: return`.
 */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class CallScreeningServiceImpl : CallScreeningService() {

    @Inject
    lateinit var appDatabase: AppDatabase

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var spamNumberRepository: SpamNumberRepository

    @Inject
    lateinit var phoneNumberNormalizer: PhoneNumberNormalizer

    @Inject
    lateinit var analyticsHelper: AnalyticsHelper

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    companion object {
        /** Канал блокировщика (общий с фильтром SMS). */
        const val CHANNEL_ID = BlockerNotifications.CHANNEL_ID

        /**
         * Отдельный id: раньше здесь стоял 1001 — тот же, что у постоянного
         * уведомления VPN, поэтому «звонок заблокирован» затирал статус брандмауэра
         * (и наоборот: обновление VPN стирало сообщение о заблокированном звонке).
         */
        const val NOTIFICATION_ID = BlockerNotifications.CALL_NOTIFICATION_ID

        /** Сколько ждём принятия решения, прежде чем пропустить звонок. */
        private const val RESPONSE_TIMEOUT_MS = 4_000L
    }

    override fun onCreate() {
        super.onCreate()
        // Канал общий с фильтром SMS: одно место создания (раньше код дублировался).
        BlockerNotifications.ensureChannel(this)
    }

    private fun showBlockedNotification(rawNumber: String?) {
        val displayNumber = rawNumber?.let { phoneNumberNormalizer.mask(it) }
            ?: getString(R.string.call_blocked_hidden_number)
        BlockerNotifications.show(
            context = this,
            notificationId = NOTIFICATION_ID,
            title = getString(R.string.call_blocked_title),
            text = getString(R.string.blocked_number, displayNumber)
        )
    }

    override fun onScreenCall(callDetails: Call.Details) {
        // handle == null для скрытых/анонимных звонков — это нормальный кейс, а не ошибка.
        val rawNumber: String? = callDetails.handle?.schemeSpecificPart
        val isHidden = rawNumber.isNullOrBlank()

        serviceScope.launch {
            // Система ждёт ответа ограниченное время (порядка 5 с), иначе звонок
            // пропускается мимо скрининга. Чтение базы + DataStore + спам-базы могло
            // не уложиться, поэтому решение ограничено таймаутом: не успели — пропускаем.
            val shouldBlock = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
                try {
                    checkShouldBlockNumber(rawNumber)
                } catch (_: Exception) {
                    false
                }
            } ?: false

            val response = CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setRejectCall(shouldBlock)
                .setSkipCallLog(shouldBlock)
                .setSkipNotification(shouldBlock)
                .build()

            // respondToCall() должен вызываться на главном потоке — из IO-корутины
            // ответ мог не доставиться, и звонок проходил без блокировки.
            withContext(Dispatchers.Main) { respondToCall(callDetails, response) }

            if (shouldBlock) {
                logBlockedCall(if (isHidden) null else rawNumber)
            }
        }
    }

    private suspend fun checkShouldBlockNumber(rawNumber: String?): Boolean {
        val blockedNumberDao = appDatabase.blockedNumberDao()
        val normalized = rawNumber?.let { phoneNumberNormalizer.canonical(it) }
        val candidates = buildList {
            rawNumber?.takeIf { it.isNotBlank() }?.let { add(it) }
            normalized?.let { add(it) }
        }

        // 1. Белый список имеет приоритет над любыми правилами блокировки.
        if (candidates.isNotEmpty() && blockedNumberDao.isWhitelistedAny(candidates)) {
            return false
        }

        // 2. Чёрный список.
        if (candidates.isNotEmpty() && blockedNumberDao.isBlacklistedAny(candidates)) {
            return true
        }

        // 3. Локальная спам-база (assets/spam_numbers.json + пользовательские записи).
        if (normalized != null && spamNumberRepository.isSpam(normalized)) {
            return true
        }

        val blockUnknown = preferencesManager.blockUnknownNumbers.first()
        val blockHidden = preferencesManager.blockHiddenNumbers.first()

        if (rawNumber.isNullOrBlank()) {
            // Скрытый номер: `handle` отсутствует либо пустой.
            return blockHidden || blockUnknown
        }

        // 4. Номер не определяется провайдером (например, "unknown"/"private").
        if (normalized == null) {
            return blockUnknown
        }

        // 5. Международные номера.
        val blockInternational = preferencesManager.blockInternationalNumbers.first()
        if (blockInternational && isForeignNumber(rawNumber, normalized)) {
            return true
        }

        return false
    }

    /** Номер считается «иностранным», если он не парсится в регионе устройства как национальный. */
    private fun isForeignNumber(rawNumber: String, normalized: String): Boolean {
        if (!rawNumber.startsWith("+")) return false
        val region = phoneNumberNormalizer.currentRegion()
        val withRegion = phoneNumberNormalizer.canonical(rawNumber) ?: return false
        val asNational = runCatching {
            // Если номер валиден как национальный для региона устройства — он «свой».
            com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
                .parse(withRegion, region)
        }.getOrNull() ?: return false
        val countryCode = asNational.countryCode
        val expected = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
            .getCountryCodeForRegion(region)
        return expected != 0 && countryCode != expected
    }

    private suspend fun logBlockedCall(rawNumber: String?) {
        val blockedLogDao = appDatabase.blockedLogDao()
        val storableNumber = rawNumber?.let { phoneNumberNormalizer.canonical(it) }
            ?: rawNumber
            ?: "hidden"

        blockedLogDao.insertBlockedLog(
            com.phoneguard.data.local.BlockedLog(
                phoneNumber = storableNumber,
                isSms = false
            )
        )
        // Номер в аналитику не передаётся — только факт блокировки звонка/SMS
        // (у метода даже нет параметра с номером).
        analyticsHelper.logCallBlocked(isSms = false)
        showBlockedNotification(rawNumber)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
