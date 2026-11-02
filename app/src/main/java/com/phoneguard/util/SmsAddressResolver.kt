package com.phoneguard.util

/**
 * Адрес получателя SMS: E.164, если номер распознаётся, иначе «компактная» строка.
 *
 * Логика вынесена из `SmsSender` по двум причинам:
 *  - там она была приватным методом внутри `object` с `Context`, то есть проверить её
 *    можно было только на устройстве;
 *  - именно в этом месте была ошибка компиляции: `PhoneNumberNormalizer.toValidE164(...)`
 *    вызывался через имя класса, хотя метод — член экземпляра (`PhoneNumberNormalizer`
 *    с `Context` в конструкторе). Офлайн-проверка этого не видела, потому что файл
 *    `SmsSender` не компилируется без Android SDK. Здесь нормализатор передаётся
 *    функцией, поэтому подменяется в тесте.
 *
 * Запасной вариант нужен для коротких номеров сервисов и буквенных отправителей
 * (bank-ID, «SBER»), которые не являются телефонами в смысле libphonenumber.
 */
object SmsAddressResolver {

    /** Максимальная длина адреса, которую принимает `SmsManager`. */
    private const val MAX_ADDRESS_LENGTH = 40

    /**
     * @param raw номер/идентификатор как он пришёл из настроек или БД
     * @param toE164 нормализация в E.164 (`PhoneNumberNormalizer::toValidE164`)
     * @return адрес для `SmsManager` либо `null`, если отправлять некуда
     */
    fun resolve(raw: String?, toE164: (String) -> String?): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return null

        // Нормализация может вернуть пустую строку или мусор — такой адрес не годится.
        toE164(candidate)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ADDRESS_LENGTH }
            ?.let { return it }

        return compact(candidate)
            .takeIf { it.length <= MAX_ADDRESS_LENGTH }
            ?.takeIf { it.any(Char::isLetterOrDigit) }
    }

    /** Убирает разделители, которые люди ставят в номерах: `+49 (170) 123-45 67`. */
    fun compact(raw: String): String = raw.filterNot { ch ->
        ch.isWhitespace() || ch == '-' || ch == '(' || ch == ')' || ch == '\u00A0'
    }
}
