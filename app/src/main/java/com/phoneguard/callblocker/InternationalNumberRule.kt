package com.phoneguard.callblocker

/**
 * Правило «номер иностранный» для настройки «блокировать международные звонки».
 *
 * Раньше логика жила прямо в `CallScreeningServiceImpl` и начиналась с
 * `if (!rawNumber.startsWith("+")) return false`. Из-за этого номер, пришедший
 * с префиксом `00` (`0049170…` — так его отдаёт часть операторов и SIP-шлюзов),
 * считался «своим», и международные звонки при включённой настройке
 * **проходили**. Теперь решение принимается по канонической форме E.164, а не
 * по тому, как номер записан во входящем вызове.
 *
 * Разбор номера вынесен за интерфейс [countryCodeOf], чтобы правило можно было
 * проверить JVM-тестом без libphonenumber.
 */
object InternationalNumberRule {

    /**
     * @param e164 канонический номер (`+491701234567`)
     * @param homeCountryCode код страны устройства (`49`); `0` — регион неизвестен
     * @param countryCodeOf код страны по номеру E.164, `null` — номер не разобран
     * @return `true`, если номер принадлежит другой стране
     */
    fun isForeign(e164: String, homeCountryCode: Int, countryCodeOf: (String) -> Int?): Boolean {
        // Регион не определён — сравнивать не с чем, лучше не блокировать звонок.
        if (homeCountryCode <= 0) return false
        if (!e164.startsWith("+")) return false
        val countryCode = countryCodeOf(e164) ?: return false
        return countryCode != homeCountryCode
    }
}
