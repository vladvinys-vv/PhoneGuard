package com.phoneguard.callblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Склейка многочастных SMS для фильтра спама.
 *
 * Проверять `BroadcastReceiver` на JVM нельзя, поэтому тестируется чистая часть:
 * одно решение на отправителя и полный текст сообщения.
 */
class SmsPartsTest {

    @Test
    fun `parts from the same sender are concatenated`() {
        val merged = SmsParts.merge(
            listOf(
                "+491701234567" to "Ваш аккаунт заблокирован. ",
                "+491701234567" to "Перейдите по ссылке http://evil.example"
            )
        )

        assertEquals(1, merged.size)
        assertEquals(
            "Ваш аккаунт заблокирован. Перейдите по ссылке http://evil.example",
            merged["+491701234567"]
        )
    }

    @Test
    fun `different senders stay separate and keep delivery order`() {
        val merged = SmsParts.merge(
            listOf(
                "+491701234567" to "first",
                "Spam-Center" to "second",
                "+491701234567" to " again"
            )
        )

        assertEquals(listOf("+491701234567", "Spam-Center"), merged.keys.toList())
        assertEquals("first again", merged["+491701234567"])
        assertEquals("second", merged["Spam-Center"])
    }

    @Test
    fun `parts without a sender are dropped`() {
        val merged = SmsParts.merge(
            listOf(
                null to "no address",
                "   " to "blank address",
                "+49170" to "real"
            )
        )

        assertEquals(listOf("+49170"), merged.keys.toList())
        assertEquals("real", merged["+49170"])
    }

    @Test
    fun `empty input yields empty result`() {
        assertTrue(SmsParts.merge(emptyList()).isEmpty())
    }
}
