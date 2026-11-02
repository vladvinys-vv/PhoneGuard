package com.phoneguard.util

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/**
 * Единая точка отправки SMS.
 *
 * Раньше отправка была скопирована в пяти местах и вела себя по-разному:
 *  - `SecurityService` и `IntrusionResponseExecutor` звали `sendTextMessage()` без
 *    разбиения — длинный текст (адрес + координаты) либо обрезался, либо падал
 *    с `IllegalArgumentException`;
 *  - `StealthCameraService` использовал только deprecated `getDefault()` — на
 *    двухсимочных аппаратах сообщение могло не уйти;
 *  - номера не нормализовались, поэтому «+49 170 …» с пробелами уходил как есть;
 *  - нигде не проверялось, что на устройстве вообще есть SMS-модуль (планшеты,
 *    Wi-Fi-only прошивки): `sendTextMessage` бросал исключение в фоне.
 *
 * Возвращает `true`, если менеджер принял сообщение к отправке.
 */
object SmsSender {

    private const val TAG = "SmsSender"

    /**
     * Нормализатор создаётся один раз на процесс: в конструкторе он определяет регион
     * по SIM (обращение к `TelephonyManager`), повторять это на каждое SMS незачем.
     */
    @Volatile
    private var normalizer: PhoneNumberNormalizer? = null

    /** Отправляет [text] на [destination]; безопасно для длинных сообщений и пустых номеров. */
    fun send(context: Context, destination: String?, text: String): Boolean {
        val address = resolveAddress(context, destination) ?: return false
        val message = text.trim()
        if (message.isEmpty()) return false

        val smsManager = smsManager(context) ?: return false
        return runCatching {
            val parts = smsManager.divideMessage(message)
            if (parts.size <= 1) {
                smsManager.sendTextMessage(address, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(address, null, parts, null, null)
            }
            true
        }.getOrElse { error ->
            // Нет разрешения, оператор запретил отправку, закончился лимит — не роняем фон.
            Log.w(TAG, "Не удалось отправить SMS", error)
            false
        }
    }

    /**
     * Адрес получателя: E.164 через [PhoneNumberNormalizer], иначе компактная строка.
     *
     * Правила живут в чистом [SmsAddressResolver] (его проверяют JVM-тесты). Здесь —
     * только создание нормализатора: раньше стояло `PhoneNumberNormalizer.toValidE164(...)`,
     * то есть вызов метода экземпляра через имя класса, и файл **не компилировался**
     * («unresolved reference 'toValidE164'»).
     */
    private fun resolveAddress(context: Context, raw: String?): String? {
        val applicationContext = context.applicationContext ?: context
        val phoneNumberNormalizer = normalizer
            ?: PhoneNumberNormalizer(applicationContext).also { normalizer = it }
        return SmsAddressResolver.resolve(raw) { candidate ->
            runCatching { phoneNumberNormalizer.toValidE164(candidate) }.getOrNull()
        }
    }

    /**
     * `SmsManager` через `getSystemService` с Android 12, иначе deprecated синглтон.
     * Возвращает `null`, если на устройстве нет телефонии.
     */
    private fun smsManager(context: Context): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }.getOrNull()
}
