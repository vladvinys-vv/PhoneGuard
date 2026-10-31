package com.phoneguard.util

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нормализация номеров (libphonenumber): от этого зависит и скрининг звонков, и
 * чёрные/белые списки, и CSV-импорт. Тесты работают на локальной metadata, без Android.
 */
class PhoneNumberNormalizerTest {

    private val normalizer = PhoneNumberNormalizer(mockk<Context>(relaxed = true))

    @Test
    fun nationalGermanMobileNumberBecomesE164() {
        assertEquals("+491701234567", normalizer.toE164("0170 1234567", "DE"))
    }

    @Test
    fun differentlyFormattedInternationalNumberNormalizesTheSame() {
        val expected = "+491701234567"
        assertEquals(expected, normalizer.toE164("+49 (170) 123-45-67", "DE"))
        assertEquals(expected, normalizer.toE164("+491701234567", "DE"))
        assertEquals(expected, normalizer.toE164("0049 170 123 45 67", "DE"))
    }

    @Test
    fun russianNumberNormalizesFromInternationalFormat() {
        assertEquals("+79123456789", normalizer.toE164("+7 (912) 345-67-89", "RU"))
    }

    @Test
    fun ussdCodesAreRejected() {
        assertNull(normalizer.toE164("*100#", "DE"))
        assertNull(normalizer.toE164("#123", "DE"))
    }

    @Test
    fun textAndShortStringsAreRejected() {
        assertNull(normalizer.toE164("private", "DE"))
        assertNull(normalizer.toE164("anonymous", "DE"))
        assertNull(normalizer.toE164("123", "DE"))
        assertNull(normalizer.toE164("   ", "DE"))
        assertNull(normalizer.toE164(null, "DE"))
    }

    @Test
    fun sameNumberIgnoresFormatting() {
        assertTrue(normalizer.sameNumber("0170 1234567", "+49 170 1234567", "DE"))
        assertTrue(normalizer.sameNumber("+49 170 1234567", "00491701234567", "DE"))
        assertFalse(normalizer.sameNumber("0170 1234567", "+49 170 7654321", "DE"))
        assertFalse(normalizer.sameNumber(null, "+491701234567", "DE"))
    }

    @Test
    fun strictValidationRejectsTooShortInternationalNumber() {
        assertNull(normalizer.toValidE164("+49 170 123", "DE"))
        assertEquals("+491701234567", normalizer.toValidE164("+49 170 1234567", "DE"))
    }

    @Test
    fun canonicalKeepsACanonicalForm() {
        assertEquals("+491701234567", normalizer.canonical("+49 (170) 1234567"))
    }

    @Test
    fun maskHidesLastFourCharacters() {
        assertEquals("+49170123••••", normalizer.mask("+491701234567"))
        assertEquals("123", normalizer.mask("123"))
        assertEquals("", normalizer.mask(null))
    }
}
