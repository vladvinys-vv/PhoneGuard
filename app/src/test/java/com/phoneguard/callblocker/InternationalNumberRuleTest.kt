package com.phoneguard.callblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило «блокировать международные звонки».
 *
 * Ключевой регресс: раньше проверка начиналась с `rawNumber.startsWith("+")`,
 * поэтому номер, пришедший с префиксом `00`, считался своим и звонок проходил.
 */
class InternationalNumberRuleTest {

    /** Устройство в Германии (+49). */
    private val germany = 49

    private fun codeOf(vararg mapping: Pair<String, Int>): (String) -> Int? {
        val table = mapping.toMap()
        return { number -> table[number] }
    }

    @Test
    fun sameCountryIsNotForeign() {
        assertFalse(
            InternationalNumberRule.isForeign("+491701234567", germany, codeOf("+491701234567" to 49))
        )
    }

    @Test
    fun otherCountryIsForeign() {
        assertTrue(
            InternationalNumberRule.isForeign("+79123456789", germany, codeOf("+79123456789" to 7))
        )
    }

    @Test
    fun numberNormalizedFromDoubleZeroIsStillChecked() {
        // Оператор отдал номер как 0049… — после канонизации это всё равно +49,
        // то есть «свой»; а 0044… раньше ошибочно считался своим.
        assertTrue(
            InternationalNumberRule.isForeign("+441632960000", germany, codeOf("+441632960000" to 44))
        )
        assertFalse(
            InternationalNumberRule.isForeign("+493012345678", germany, codeOf("+493012345678" to 49))
        )
    }

    @Test
    fun unknownRegionNeverBlocks() {
        assertFalse(
            "если регион не определён, звонки блокировать нельзя",
            InternationalNumberRule.isForeign("+79123456789", homeCountryCode = 0) { 7 }
        )
    }

    @Test
    fun unparsableNumberIsNotTreatedAsForeign() {
        // fallback-форма канонизации: "+0170…" — libphonenumber такой номер не разберёт.
        assertFalse(InternationalNumberRule.isForeign("+0170123456", germany) { null })
    }

    @Test
    fun nonE164InputIsNotTreatedAsForeign() {
        assertFalse(InternationalNumberRule.isForeign("491701234567", germany) { 7 })
    }
}
