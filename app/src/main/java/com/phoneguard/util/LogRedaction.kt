package com.phoneguard.util

/**
 * Скрытие персональных данных в выгрузке журналов.
 *
 * Выгрузка (`LogExporter` → `ExportJson`) — единственный файл, который пользователь отдаёт
 * наружу: в поддержку, в отчёт об ошибке, в переписку. В журналах при этом лежат чужие
 * телефонные номера (журнал блокировок) и IP-адреса (журнал фаервола), то есть данные не
 * пользователя, а третьих лиц. Отдавать их целиком нельзя, а совсем убирать бесполезно —
 * по ним разбираются инциденты. Поэтому маскируем середину: видно код страны/оператора и
 * последние две цифры, по которым пользователь узнаёт свой случай, но номер не восстановить.
 *
 * Исходные записи в базе не меняются: маскирование происходит только при сериализации.
 */
internal object LogRedaction {

    /** Символ маски (не `*`, чтобы не путать с wildcard в доменах). */
    const val MASK_CHAR = '\u2022'

    /** Сколько первых цифр номера оставляем: код страны + первая цифра оператора. */
    private const val KEEP_PREFIX_DIGITS = 3

    /** Сколько последних цифр оставляем — по ним пользователь узнаёт свой номер. */
    private const val KEEP_SUFFIX_DIGITS = 2

    /** Номер короче этого не считается телефонным (короткие коды, `*100#`, служебные). */
    private const val MIN_PHONE_DIGITS = 6

    /**
     * Маскирует телефонный номер: `+491701234567` → `+491•••••••67`.
     *
     * Разделители (пробелы, дефисы, скобки) при маскировании теряются — так номер нельзя
     * восстановить по «рисунку». Слишком короткие строки и USSD-коды маскируются целиком:
     * в них нет ничего, кроме самого кода, и сохранять там нечего.
     */
    fun maskPhoneNumber(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < MIN_PHONE_DIGITS) return MASK_CHAR.toString().repeat(3)
        val sign = if (trimmed.startsWith("+")) "+" else ""
        val hidden = digits.length - KEEP_PREFIX_DIGITS - KEEP_SUFFIX_DIGITS
        return sign + digits.take(KEEP_PREFIX_DIGITS) + MASK_CHAR.toString().repeat(hidden) +
            digits.takeLast(KEEP_SUFFIX_DIGITS)
    }

    /**
     * Маскирует IP-адрес.
     *
     * IPv4: остаются первые два октета (`192.168.•.•`) — по ним видно провайдера и подсеть,
     * но не конкретное устройство. IPv6: остаётся только первая группа.
     */
    fun maskIp(raw: String?): String? {
        if (raw == null) return null
        val value = raw.trim()
        if (value.isEmpty()) return value
        val mask = MASK_CHAR.toString()
        if (value.contains(':')) {
            val first = value.substringBefore(':')
            return if (first.isEmpty() || first == value) "$mask$mask$mask" else "$first::$mask$mask"
        }
        val octets = value.split('.')
        return if (octets.size == 4 && octets.all { it.isNotEmpty() }) {
            "${octets[0]}.${octets[1]}.$mask.$mask"
        } else {
            // Не IPv4 и не IPv6: не угадываем формат, прячем целиком.
            "$mask$mask$mask"
        }
    }
}
