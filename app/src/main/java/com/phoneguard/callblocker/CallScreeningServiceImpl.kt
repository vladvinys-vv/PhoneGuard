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

    /**
     * Собирает факты о звонке и отдаёт решение [CallBlockDecision].
     *
     * Чтения БД и настроек идут с короткими замыканиями: если номер в белом списке,
     * остальные запросы не выполняются вовсе (система ждёт ответа ограниченное время).
     */
    private suspend fun checkShouldBlockNumber(rawNumber: String?): Boolean {
        val blockedNumberDao = appDatabase.blockedNumberDao()
        val normalized = rawNumber?.let { phoneNumberNormalizer.canonical(it) }
        val candidates = buildList {
            rawNumber?.takeIf { it.isNotBlank() }?.let { add(it) }
            normalized?.let { add(it) }
        }

        val whitelisted = candidates.isNotEmpty() && blockedNumberDao.isWhitelistedAny(candidates)
        val blacklisted = !whitelisted && candidates.isNotEmpty() &&
            blockedNumberDao.isBlacklistedAny(candidates)
        val spamDatabase = !whitelisted && !blacklisted && normalized != null &&
            spamNumberRepository.isSpam(normalized)

        val hidden = rawNumber.isNullOrBlank()
        // Настройки нужны только если номер не отсечён списками и спам-базой.
        val needsPolicy = !whitelisted && !blacklisted && !spamDatabase
        val blockUnknown = needsPolicy && preferencesManager.blockUnknownNumbers.first()
        val blockHidden = needsPolicy && hidden && preferencesManager.blockHiddenNumbers.first()
        val blockInternational = needsPolicy && !hidden && normalized != null &&
            preferencesManager.blockInternationalNumbers.first()

        return CallBlockDecision.shouldBlock(
            CallBlockDecision.Facts(
                whitelisted = whitelisted,
                blacklisted = blacklisted,
                spamDatabase = spamDatabase,
                callerIdHidden = hidden,
                numberParsed = normalized != null,
                blockUnknown = blockUnknown,
                blockHidden = blockHidden,
                blockInternational = blockInternational,
                foreignNumber = blockInternational && isForeignNumber(normalized.orEmpty())
            )
        )
    }

    /**
     * Номер «иностранный», если его код страны отличается от кода региона устройства.
     *
     * Проверяется каноническая форма, а не исходная запись: номер с префиксом `00`
     * раньше не блокировался вовсе. Само правило — в [InternationalNumberRule].
     */
    private fun isForeignNumber(normalized: String): Boolean {
        val region = phoneNumberNormalizer.currentRegion()
        val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
        return InternationalNumberRule.isForeign(
            e164 = normalized,
            homeCountryCode = util.getCountryCodeForRegion(region)
        ) { number ->
            runCatching { util.parse(number, region).countryCode }.getOrNull()
        }
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
