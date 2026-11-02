package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маскирование персональных данных в выгрузке журналов.
 *
 * Выгрузка — единственный файл, который уходит наружу, а в журналах лежат номера третьих лиц
 * и IP-адреса. Правила проверяются здесь, потому что цена ошибки в них — либо утечка номера
 * в чужой чат, либо выгрузка, по которой нельзя разобрать инцидент.
 */
class LogRedactionTest {

    private val mask = LogRedaction.MASK_CHAR

    @Test
    fun internationalNumberKeepsPrefixAndLastTwoDigits() {
        assertEquals("+491${mask.toString().repeat(7)}67", LogRedaction.maskPhoneNumber("+491701234567"))
    }

    @Test
    fun separatorsDoNotSurviveMasking() {
        // Иначе по «рисунку» номера (скобки, дефисы) можно восстановить формат записи.
        val masked = LogRedaction.maskPhoneNumber("+49 (170) 123-45-67")
        assertEquals("+491${mask.toString().repeat(7)}67", masked)
        assertFalse(masked!!.contains('('))
        assertFalse(masked.contains('-'))
    }

    @Test
    fun nationalNumberWithoutPlusKeepsItsDigitsOnly() {
        // «0170 1234567» — 11 цифр: 3 в префиксе, 6 под маской, 2 в хвосте.
        assertEquals("017${mask.toString().repeat(6)}67", LogRedaction.maskPhoneNumber("0170 1234567"))
    }

    @Test
    fun shortCodesAreFullyMasked() {
        // USSD и короткие номера не содержат ничего, кроме самого кода.
        assertEquals("$mask$mask$mask", LogRedaction.maskPhoneNumber("*100#"))
        assertEquals("$mask$mask$mask", LogRedaction.maskPhoneNumber("12345"))
        assertEquals("$mask$mask$mask", LogRedaction.maskPhoneNumber("123"))
    }

    @Test
    fun missingNumberStaysMissing() {
        assertNull(LogRedaction.maskPhoneNumber(null))
        // Пустая строка остаётся пустой, а «пробельная» приходит уже обрезанной:
        // в выгрузке это `""`, а не три пробела — маскировать там нечего.
        assertEquals("", LogRedaction.maskPhoneNumber(""))
        assertEquals("", LogRedaction.maskPhoneNumber("   "))
    }

    @Test
    fun maskingIsIrreversible() {
        val original = "+491701234567"
        val masked = LogRedaction.maskPhoneNumber(original)!!
        assertEquals("длина маски равна длине цифр (плюс знак)", original.length, masked.length)
        assertFalse("в маске не должно остаться исходного номера", masked.contains(original))
        assertFalse(masked.contains("17012345"))
    }

    @Test
    fun ipv4KeepsFirstTwoOctets() {
        assertEquals("192.168.$mask.$mask", LogRedaction.maskIp("192.168.1.42"))
    }

    @Test
    fun ipv6KeepsOnlyFirstGroup() {
        assertEquals("fe80::$mask$mask", LogRedaction.maskIp("fe80::1ff:fe23:4567:890a"))
    }

    @Test
    fun malformedAddressIsMaskedCompletely() {
        // Не угадываем формат: если это не IPv4/IPv6, прячем строку целиком.
        assertEquals("$mask$mask$mask", LogRedaction.maskIp("not-an-ip"))
        assertEquals("$mask$mask$mask", LogRedaction.maskIp("1.2.3"))
        assertEquals("$mask$mask$mask", LogRedaction.maskIp("::"))
    }

    @Test
    fun missingIpStaysMissing() {
        assertNull(LogRedaction.maskIp(null))
        assertEquals("", LogRedaction.maskIp(""))
        assertEquals("", LogRedaction.maskIp("  "))
    }

    @Test
    fun ipMaskingLeavesNoFullAddress() {
        val masked = LogRedaction.maskIp("10.20.30.40")!!
        assertFalse("последние октеты не должны утекать", masked.contains("30"))
        assertFalse(masked.contains("40"))
        assertTrue(masked.startsWith("10.20."))
    }
}
