package com.phoneguard.callblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import com.phoneguard.R
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.data.repository.SpamNumberRepository
import com.phoneguard.fullscan.checks.PhishingUrlChecker
import com.phoneguard.util.AnalyticsHelper
import com.phoneguard.util.PhoneNumberNormalizer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Фильтр входящих SMS: чёрный список, белый список и локальная спам-база.
 *
 * Почему этого не хватало: экран обещал «Блокировщик звонков **& SMS**», журнал
 * блокировок умел `isSms = true`, но в приложении был только `SmsCommandReceiver`
 * (анти-воровские команды) — входящие SMS не проверялись вообще.
 *
 * Как это работает:
 *  - `SMS_RECEIVED` — упорядоченная рассылка, поэтому фильтр успевает посмотреть
 *    сообщение до системного приложения SMS;
 *  - отменить доставку (`abortBroadcast`) может **только** приложение SMS по
 *    умолчанию (Android 4.4+). Если PhoneGuard им не выбран, сообщение всё равно
 *    помечается в журнале и появляется уведомление «SMS от заблокированного
 *    номера» — без ложного обещания, что сообщение не придёт;
 *  - многочастные SMS склеиваются по отправителю ([SmsParts.merge]): решение
 *    принимается один раз, а текст проверяется целиком — спам-ссылка часто
 *    оказывается именно во второй части;
 *  - кроме списков работает локальная эвристика фишинга
 *    ([PhishingUrlChecker.isSuspicious], без сети): пользователь получает
 *    предупреждение даже о новом номере. Такое сообщение **не отменяется** —
 *    эвристика ошибается, и молча съедать чужую SMS нельзя.
 *
 * Получатель объявлен с `android:permission="android.permission.BROADCAST_SMS"`,
 * поэтому посторонние приложения не могут подделать событие.
 */
@AndroidEntryPoint
class SmsSpamReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appDatabase: AppDatabase

    @Inject
    lateinit var spamNumberRepository: SpamNumberRepository

    @Inject
    lateinit var phoneNumberNormalizer: PhoneNumberNormalizer

    @Inject
    lateinit var analyticsHelper: AnalyticsHelper

    /** Локальная эвристика «ссылка + давящие слова» (без сетевых запросов). */
    @Inject
    lateinit var phishingUrlChecker: PhishingUrlChecker

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // parsePDU переехал в другую перегрузку в API 33+ — используем
        // совместимый `getMessagesFromIntent`, который сам выбирает нужный путь.
        val parts = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                .map { message -> message.originatingAddress to message.displayMessageBody }
        }.getOrNull() ?: return

        if (parts.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                processMessages(context, parts)
            } catch (e: Exception) {
                // Падение фильтра не должно мешать доставке SMS.
                Log.w(TAG, "SMS spam filter failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processMessages(context: Context, parts: List<Pair<String?, String>>) {
        val blockedNumberDao = appDatabase.blockedNumberDao()
        // Спам именно по номеру — только тогда допустимо отменить доставку.
        var blockedByNumber = false

        for ((rawSender, text) in SmsParts.merge(parts)) {
            val normalized = phoneNumberNormalizer.canonical(rawSender)
            val candidates = buildList {
                add(rawSender)
                normalized?.takeIf { it != rawSender }?.let { add(it) }
            }

            // Белый список важнее любых правил: его выставляет сам пользователь.
            if (blockedNumberDao.isWhitelistedAny(candidates)) continue

            val blockedByRules = blockedNumberDao.isBlacklistedAny(candidates) ||
                (normalized != null && spamNumberRepository.isSpam(normalized))
            val suspicious = runCatching { phishingUrlChecker.isSuspicious(text) }.getOrDefault(false)

            if (!blockedByRules && !suspicious) continue

            if (blockedByRules) blockedByNumber = true

            val stored = normalized ?: rawSender
            runCatching {
                appDatabase.blockedLogDao().insertBlockedLog(
                    BlockedLog(phoneNumber = stored, isSms = true)
                )
            }
            analyticsHelper.logCallBlocked(isSms = true)
            // Номер маскируется: уведомление видно на экране блокировки.
            val masked = phoneNumberNormalizer.mask(stored)
            BlockerNotifications.show(
                context = context,
                notificationId = BlockerNotifications.SMS_NOTIFICATION_ID,
                title = context.getString(
                    if (blockedByRules) R.string.sms_blocked_title else R.string.sms_suspicious_title
                ),
                text = context.getString(
                    if (blockedByRules) R.string.sms_blocked_text else R.string.sms_suspicious_text,
                    masked
                )
            )
        }

        // Отменяем доставку только при блокировке по номеру: эвристика фишинга
        // недостаточно надёжна, чтобы «съесть» сообщение целиком.
        if (blockedByNumber && isDefaultSmsApp(context)) {
            // Работает только для приложения SMS по умолчанию; в остальных случаях
            // система уже доставила сообщение — мы лишь предупредили пользователя.
            runCatching { abortBroadcast() }
                .onFailure { Log.w(TAG, "abortBroadcast is not allowed for a non-default SMS app") }
        }
    }

    private fun isDefaultSmsApp(context: Context): Boolean {
        val defaultPackage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            context.getSystemService(TelephonyManager::class.java)?.defaultSmsPackage
        } else {
            @Suppress("DEPRECATION")
            Telephony.Sms.getDefaultSmsPackage(context)
        }
        return defaultPackage == context.packageName
    }

    private companion object {
        const val TAG = "SmsSpamReceiver"
    }
}
