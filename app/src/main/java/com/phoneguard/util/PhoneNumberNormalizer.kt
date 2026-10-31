package com.phoneguard.util

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Нормализация телефонных номеров на базе [libphonenumber](https://github.com/google/libphonenumber).
 *
 * Зачем это нужно PhoneGuard:
 *  - чёрные/белые списки должны совпадать с входящими номерами в любом формате
 *    (`+49 170 1234567`, `0170 1234567`, `0170/1234567`, `+7 (912) 345-67-89`);
 *  - спам-база в assets хранит номера в одном формате, а сравнивать нужно с любым входящим;
 *  - CSV-импорт должен отбраковывать мусор до записи в БД.
 *
 * Библиотека оффлайн: используется только локальная metadata, никаких сетевых вызовов.
 */
@Singleton
class PhoneNumberNormalizer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val phoneNumberUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    /**
     * Регион по умолчанию (ISO-3166 alpha-2), который используется для парсинга
     * номеров без международного префикса. Определяется по SIM, затем по локали.
     */
    private val defaultRegion: String by lazy { detectDefaultRegion() }

    /**
     * Приводит номер к формату E.164 (`+491701234567`).
     *
     * @return E.164-номер либо `null`, если номер распознать нельзя.
     */
    fun toE164(rawNumber: String?, region: String? = defaultRegion): String? {
        val candidate = sanitize(rawNumber) ?: return null
        return parse(candidate, region)?.let { formatE164(it) }
            ?: fallbackDigits(candidate)
    }

    /**
     * Строгий вариант: возвращает E.164 только если номер валиден для своего региона
     * (проверяется длина и префиксы по актуальной metadata libphonenumber).
     */
    fun toValidE164(rawNumber: String?, region: String? = defaultRegion): String? {
        val candidate = sanitize(rawNumber) ?: return null
        val parsed = parse(candidate, region) ?: return fallbackDigits(candidate)
        return if (phoneNumberUtil.isValidNumber(parsed)) formatE164(parsed) else null
    }

    /** Является ли строка похожей на телефонный номер. */
    fun looksLikePhoneNumber(rawNumber: String?): Boolean = toE164(rawNumber) != null

    /** Валиден ли номер по metadata libphonenumber (длина и префиксы для региона). */
    fun isValid(rawNumber: String?, region: String? = defaultRegion): Boolean {
        val candidate = sanitize(rawNumber) ?: return false
        val parsed = parse(candidate, region) ?: return false
        return phoneNumberUtil.isValidNumber(parsed)
    }

    /**
     * Сравнение двух номеров с учётом формата записи.
     * Если хотя бы один номер не удалось нормализовать — сравниваем «сырые» строки цифрами.
     */
    fun sameNumber(first: String?, second: String?, region: String? = defaultRegion): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        val a = toE164(first, region) ?: return digitsOnly(first) == digitsOnly(second)
        val b = toE164(second, region) ?: return digitsOnly(first) == digitsOnly(second)
        return a == b
    }

    /**
     * Каноническая форма для хранения в БД (чёрные/белые списки, спам-база).
     * Всегда возвращает непустую строку: если номер не распознан,
     * сохраняем только цифры — так поиск в БД остаётся предсказуемым.
     */
    fun canonical(rawNumber: String?): String? {
        val candidate = sanitize(rawNumber) ?: return null
        return toE164(candidate) ?: fallbackDigits(candidate)
    }

    /** Маска для отображения в UI: последние четыре символа скрываются — `+491701234567` → `+49170123••••`. */
    fun mask(number: String?): String {
        val trimmed = number?.trim().orEmpty()
        if (trimmed.length <= 4) return trimmed
        val visiblePrefix = trimmed.take(trimmed.length - 4)
        return "$visiblePrefix••••"
    }

    /** ISO-код региона, который будет применяться при парсинге локальных номеров. */
    fun currentRegion(): String = defaultRegion

    private fun parse(candidate: String, region: String?): Phonenumber.PhoneNumber? {
        val effectiveRegion = region?.takeIf { it.length == 2 } ?: defaultRegion
        return try {
            phoneNumberUtil.parse(candidate, effectiveRegion)
        } catch (_: NumberParseException) {
            null
        }
    }

    private fun formatE164(number: Phonenumber.PhoneNumber): String =
        phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)

    /**
     * Оставляет только цифры и ведущий `+`.
     * Отсекает строки, которые заведомо не являются телефонным номером
     * (`*100#`, USSD, «private», «anonymous», буквенный мусор).
     */
    private fun sanitize(rawNumber: String?): String? {
        val input = rawNumber?.trim().orEmpty()
        if (input.isEmpty()) return null
        if (input.any { it == '#' || it == '*' }) return null
        if (input.any { it.isLetter() }) return null

        val digits = input.filter { it.isDigit() }
        if (digits.length < 4) return null
        return if (input.startsWith("+")) "+$digits" else digits
    }

    /** Последний рубеж: если libphonenumber не смог разобрать номер — храним E.164-подобную форму. */
    private fun fallbackDigits(number: String): String? {
        val digits = digitsOnly(number)
        if (digits.length < 4 || digits.length > MAX_E164_DIGITS) return null
        return "+$digits"
    }

    private fun digitsOnly(number: String): String = number.filter { it.isDigit() }

    @Suppress("MissingPermission")
    private fun detectDefaultRegion(): String {
        val fromSim = runCatching {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephony?.simCountryIso?.uppercase(Locale.US)
        }.getOrNull()?.takeIf { it.length == 2 }

        val fromNetwork = runCatching {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephony?.networkCountryIso?.uppercase(Locale.US)
        }.getOrNull()?.takeIf { it.length == 2 }

        val fromLocale = runCatching { Locale.getDefault().country.uppercase(Locale.US) }
            .getOrNull()?.takeIf { it.length == 2 }

        return fromSim ?: fromNetwork ?: fromLocale ?: DEFAULT_REGION
    }

    companion object {
        /** Германия — основной рынок проекта (см. README), используется как последний fallback. */
        const val DEFAULT_REGION = "DE"

        /** Максимальная длина E.164 — 15 цифр без учёта `+`. */
        private const val MAX_E164_DIGITS = 15
    }
}
