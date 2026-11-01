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

    @Test
    fun partsFromDifferentSendersStaySeparate() {
        val merged = SmsParts.merge(
            listOf(
                "+491111" to "first ",
                "+492222" to "second",
                "+491111" to "message"
            )
        )
        assertEquals(2, merged.size)
        assertEquals("first message", merged["+491111"])
        assertEquals("second", merged["+492222"])
    }

    @Test
    fun partWithoutSenderIsDropped() {
        val merged = SmsParts.merge(listOf(null to "orphan", "   " to "blank", "+491111" to "ok"))
        assertEquals(mapOf("+491111" to "ok"), merged)
    }

    @Test
    fun senderAddressIsTrimmedWhenMerging() {
        val merged = SmsParts.merge(listOf(" +491111 " to "a", "+491111" to "b"))
        assertEquals(1, merged.size)
        assertEquals("ab", merged["+491111"])
    }

    @Test
    fun deliveryOrderIsPreserved() {
        val merged = SmsParts.merge(listOf("b" to "1", "a" to "2", "b" to "3"))
        assertEquals(listOf("b", "a"), merged.keys.toList())
        assertEquals("13", merged["b"])
    }

    @Test
    fun emptyInputGivesEmptyMap() {
        assertTrue(SmsParts.merge(emptyList()).isEmpty())
    }

    @Test
    fun emptyTextPartsDoNotAddGarbage() {
        val merged = SmsParts.merge(listOf("+491111" to "", "+491111" to "x"))
        assertEquals("x", merged["+491111"])
    }
}
