package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правила «что считать номером» — та часть нормализации, которая раньше не проверялась.
 *
 * Эти проверки исполняются в офлайн-прогоне (без libphonenumber и без `Context`): именно они
 * решают, попадёт ли строка в чёрный список или в спам-базу. Ошибка здесь тише, чем в парсинге:
 * лишняя строка в базе совпадает со входящими случайно, а потерянная — не блокирует спам.
 */
class PhoneNumberFormatTest {

    @Test
    fun `national number keeps digits without plus`() {
        assertEquals("01701234567", PhoneNumberFormat.sanitize("0170 1234567"))
    }

    @Test
    fun `international number keeps single leading plus`() {
        assertEquals("+491701234567", PhoneNumberFormat.sanitize("+49 (170) 123-45-67"))
    }

    @Test
    fun `punctuation inside the number does not matter`() {
        assertEquals("+491701234567", PhoneNumberFormat.sanitize("+49/170/123 45 67"))
        assertEquals("79123456789", PhoneNumberFormat.sanitize("7 (912) 345-67-89"))
    }

    @Test
    fun `ussd codes are not phone numbers`() {
        assertNull(PhoneNumberFormat.sanitize("*100#"))
        assertNull(PhoneNumberFormat.sanitize("#123"))
        assertNull(PhoneNumberFormat.sanitize("+49170*123"))
    }

    @Test
    fun `letters are rejected even in international format`() {
        assertNull(PhoneNumberFormat.sanitize("private"))
        assertNull(PhoneNumberFormat.sanitize("anonymous"))
        assertNull(PhoneNumberFormat.sanitize("+49 CALL-ME"))
    }

    @Test
    fun `too short strings are rejected`() {
        assertNull(PhoneNumberFormat.sanitize("123"))
        assertNull(PhoneNumberFormat.sanitize("  12  "))
    }

    @Test
    fun `blank and null are rejected`() {
        assertNull(PhoneNumberFormat.sanitize(null))
        assertNull(PhoneNumberFormat.sanitize(""))
        assertNull(PhoneNumberFormat.sanitize("   "))
    }

    @Test
    fun `plus is kept only when it was at the start`() {
        // `0049…` — международный префикс без плюса: цифры сохраняются как есть, и уже
        // libphonenumber решает, что это Германия.
        assertEquals("00491701234567", PhoneNumberFormat.sanitize("0049 170 1234567"))
        // Плюс в середине — не международный префикс.
        assertEquals("123456", PhoneNumberFormat.sanitize("12+3456"))
    }

    @Test
    fun `sanitize keeps exactly the digits of a long number`() {
        assertEquals("+491701234567", PhoneNumberFormat.sanitize(" +49 170 1234567 "))
    }

    @Test
    fun `digitsOnly drops everything but digits`() {
        assertEquals("491701234567", PhoneNumberFormat.digitsOnly("+49 (170) 12-34-567"))
        assertEquals("", PhoneNumberFormat.digitsOnly("no digits"))
    }

    @Test
    fun `fallback builds e164 from plain digits`() {
        assertEquals("+491701234567", PhoneNumberFormat.fallbackE164("+49 170 1234567"))
    }

    @Test
    fun `fallback drops the international prefix instead of doubling the zero`() {
        // `00` — IDD-префикс: без его отбрасывания в базе оказалось бы `+0049…`, что не
        // совпадёт ни с одним входящим номером.
        assertEquals("+491701234567", PhoneNumberFormat.fallbackE164("0049 170 1234567"))
        assertEquals("+79123456789", PhoneNumberFormat.fallbackE164("007 912 345 67 89"))
    }

    @Test
    fun `fallback rejects numbers that cannot be e164`() {
        assertNull(PhoneNumberFormat.fallbackE164("123"))
        // 16 цифр — за пределом E.164 (15), такой номер нельзя хранить как международный.
        assertNull(PhoneNumberFormat.fallbackE164("1234567890123456"))
    }

    @Test
    fun `fallback accepts the e164 boundary`() {
        val fifteen = "123456789012345"
        assertEquals("+$fifteen", PhoneNumberFormat.fallbackE164(fifteen))
    }

    @Test
    fun `mask hides last four characters`() {
        assertEquals("+49170123••••", PhoneNumberFormat.mask("+491701234567"))
    }

    @Test
    fun `mask leaves short numbers untouched`() {
        assertEquals("1234", PhoneNumberFormat.mask("1234"))
        assertEquals("", PhoneNumberFormat.mask(null))
        assertEquals("", PhoneNumberFormat.mask("   "))
    }

    @Test
    fun `mask trims spaces before masking`() {
        assertEquals("+4917••••", PhoneNumberFormat.mask("  +49170123  "))
    }
}
