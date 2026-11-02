package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Адрес получателя SMS.
 *
 * Логика вынесена из `SmsSender`, где жила приватным методом внутри `object` с
 * `Context` — и где, как выяснилось, стоял вызов метода экземпляра через имя класса
 * (файл не компилировался).
 */
class SmsAddressResolverTest {

    /** Заглушка libphonenumber: «распознаёт» только номера, начинающиеся с `+`. */
    private val fakeE164: (String) -> String? = { raw ->
        val digits = raw.filter { it.isDigit() }
        if (raw.trimStart().startsWith("+") && digits.length in 7..15) "+$digits" else null
    }

    @Test
    fun `международный номер приводится к E164`() {
        assertEquals(
            "+491701234567",
            SmsAddressResolver.resolve("+49 170 1234567", fakeE164)
        )
    }

    @Test
    fun `разделители убираются из нераспознанного номера`() {
        // Нормализатор не смог (нет плюса) — уходит компактная строка.
        assertEquals("01701234567", SmsAddressResolver.resolve("0170 123-45 67", fakeE164))
        assertEquals("01701234567", SmsAddressResolver.resolve("0170 (123) 45 67", fakeE164))
    }

    @Test
    fun `буквенный отправитель сохраняется, но без пробелов`() {
        assertEquals("SBERBANK", SmsAddressResolver.resolve("SBERBANK", fakeE164))
        // Пробелы внутри адреса `SmsManager` не принимает, поэтому они уходят вместе
        // с остальными разделителями; буквы и цифры остаются.
        assertEquals("SberBank", SmsAddressResolver.resolve("  Sber Bank ", fakeE164))
    }

    @Test
    fun `пустой адрес не отправляется`() {
        assertNull(SmsAddressResolver.resolve(null, fakeE164))
        assertNull(SmsAddressResolver.resolve("", fakeE164))
        assertNull(SmsAddressResolver.resolve("   \n\t ", fakeE164))
    }

    @Test
    fun `строка из одних разделителей не является адресом`() {
        assertNull(SmsAddressResolver.resolve("---", fakeE164))
        assertNull(SmsAddressResolver.resolve("() ()", fakeE164))
    }

    @Test
    fun `пустой результат нормализатора игнорируется`() {
        assertEquals("0170", SmsAddressResolver.resolve("0170", { "" }))
        assertNull(SmsAddressResolver.resolve("   ", { "  " }))
    }

    @Test
    fun `слишком длинный адрес не отправляется`() {
        val long = "a".repeat(60)
        assertNull(SmsAddressResolver.resolve(long, fakeE164))
        // Ровно на границе — допустим.
        assertEquals("a".repeat(40), SmsAddressResolver.resolve("a".repeat(40), fakeE164))
    }

    @Test
    fun `неразрывный пробел тоже разделитель`() {
        assertEquals("+49170123", SmsAddressResolver.resolve("+49\u00A0170 123", fakeE164))
    }

    @Test
    fun `compact убирает только разделители`() {
        assertEquals("+49170", SmsAddressResolver.compact("+49 (170)"))
        assertEquals("SBERBANK", SmsAddressResolver.compact("SBERBANK"))
        assertEquals("", SmsAddressResolver.compact(" - - "))
    }
}
