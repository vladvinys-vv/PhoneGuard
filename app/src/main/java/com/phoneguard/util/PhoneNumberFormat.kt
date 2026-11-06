package com.phoneguard.util

/**
 * Чистые правила телефонных номеров: что считать номером, как его хранить и как маскировать.
 *
 * Вынесено из [PhoneNumberNormalizer]: сам нормализатор держится на libphonenumber и `Context`
 * (регион по SIM), а эти правила — обычные строковые операции, и их можно исполнять и
 * проверять без Android. Раньше они были видны только тестам, которые в офлайн-прогоне не
 * запускаются, то есть не проверялись вообще.
 *
 * Разделение простое: здесь — «похоже ли на номер и в каком виде хранить», в нормализаторе —
 * «какой это номер по metadata libphonenumber».
 */
object PhoneNumberFormat {

    /** Максимальная длина E.164 — 15 цифр без учёта `+`. */
    const val MAX_E164_DIGITS = 15

    /** Минимальная длина, которую вообще имеет смысл считать номером. */
    const val MIN_DIGITS = 4

    /**
     * Оставляет только цифры и ведущий `+`, отбрасывая всё, что заведомо не номер.
     *
     * Отсекаются USSD-коды (`*100#`), служебные строки («private», «anonymous») и буквенный
     * мусор: если такое попадёт в базу, оно будет совпадать со входящими вызовами случайно.
     *
     * @return `+<цифры>` для международного формата, `<цифры>` для национального, `null` — если
     *     это не номер (пусто, есть `*`/`#`, есть буквы, меньше [MIN_DIGITS] цифр).
     */
    fun sanitize(rawNumber: String?): String? {
        val input = rawNumber?.trim().orEmpty()
        if (input.isEmpty()) return null
        if (input.any { it == '#' || it == '*' }) return null
        if (input.any { it.isLetter() }) return null

        val digits = digitsOnly(input)
        if (digits.length < MIN_DIGITS) return null
        return if (input.startsWith("+")) "+$digits" else digits
    }

    fun digitsOnly(number: String): String = number.filter { it.isDigit() }

    /**
     * Последний рубеж, когда libphonenumber номер не разобрал: храним «E.164-подобную» форму.
     *
     * Без этого номер, который библиотека не знает (новый диапазон, нестандартный формат),
     * попал бы в базу как есть — и не совпал бы ни с одним входящим.
     */
    fun fallbackE164(number: String): String? {
        // `00` — международный префикс (IDD). Без его отбрасывания номер, записанный как
        // `0049 170 …`, сохранился бы в базу строкой `+0049170…` и не совпал бы ни с одним
        // входящим вызовом: в E.164 двойного нуля быть не может.
        val digits = digitsOnly(number).removePrefix(IDD_PREFIX)
        if (digits.length < MIN_DIGITS || digits.length > MAX_E164_DIGITS) return null
        return "+$digits"
    }

    /**
     * Маска для показа в интерфейсе: последние четыре символа скрыты.
     *
     * Длина считается по символам, а не по цифрам: `+49170123••••` — так же, как номер выглядел
     * в списке, поэтому строку не приходится пересобирать заново.
     */
    fun mask(number: String?): String {
        val trimmed = number?.trim().orEmpty()
        if (trimmed.length <= MASK_VISIBLE_TAIL) return trimmed
        return trimmed.take(trimmed.length - MASK_VISIBLE_TAIL) + MASK
    }

    private const val IDD_PREFIX = "00"
    private const val MASK_VISIBLE_TAIL = 4
    private const val MASK = "••••"
}
