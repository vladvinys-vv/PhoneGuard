package com.phoneguard.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Копирование в сейф с лимитом размера.
 *
 * Проверка «лимит 50 МБ не обходится» раньше жила внутри `EncryptionManager` (Context +
 * ContentResolver + AndroidKeyStore) и не исполнялась. Ошибка на единицу здесь означает либо
 * «файл ровно 50 МБ отвергнут», либо «сейф можно забить файлом на несколько гигабайт».
 */
class VaultStreamsTest {

    private fun copy(payload: ByteArray, maxBytes: Long): Pair<Long, ByteArray> {
        val output = ByteArrayOutputStream()
        val written = VaultStreams.copyWithLimit(ByteArrayInputStream(payload), output, maxBytes)
        return written to output.toByteArray()
    }

    @Test
    fun copiesEverythingAndReportsTheSize() {
        val payload = ByteArray(1000) { it.toByte() }
        val (written, content) = copy(payload, maxBytes = 10_000)
        assertEquals(1000L, written)
        assertArrayEquals(payload, content)
    }

    @Test
    fun fileExactlyAtTheLimitIsAccepted() {
        val payload = ByteArray(500)
        val (written, content) = copy(payload, maxBytes = 500)
        assertEquals(500L, written)
        assertEquals(500, content.size)
    }

    @Test
    fun oneByteOverTheLimitIsRejected() {
        val payload = ByteArray(501)
        assertThrows(VaultFileTooLargeException::class.java) {
            copy(payload, maxBytes = 500)
        }
    }

    @Test
    fun emptyFileIsAllowedEvenWithZeroLimit() {
        val (written, content) = copy(ByteArray(0), maxBytes = 0)
        assertEquals(0L, written)
        assertEquals(0, content.size)
    }

    @Test
    fun oversizedFileLeavesPartialOutputForCallerToDelete() {
        // Данных больше одного буфера: первые целые чанки попадают в поток, и вызывающий
        // обязан удалить такой файл (иначе в сейфе останется обрезанный «успешный» файл).
        // Размер выбран так, чтобы 8192-байтный чанк влез в лимит, а следующий — уже нет.
        val payload = ByteArray(20_000)
        val output = ByteArrayOutputStream()
        assertThrows(VaultFileTooLargeException::class.java) {
            VaultStreams.copyWithLimit(ByteArrayInputStream(payload), output, maxBytes = 9_000)
        }
        assertTrue("часть данных уже записана — файл нужно удалить", output.size() > 0)
        assertEquals("в файл попал ровно один целый чанк", 8_192, output.size())
    }

    @Test
    fun streamNeverExceedsTheLimitEvenOnFailure() {
        // Инвариант, который важнее конкретных чисел: при отказе записанное не превышает
        // лимит. Чанк, пересекающий границу, не пишется — иначе лимит можно превысить на
        // размер буфера (и именно так «50 МБ» превращаются в «50 МБ + 8 КБ»).
        listOf(1, 100, 4_096, 8_192).forEach { limit ->
            val output = ByteArrayOutputStream()
            assertThrows(VaultFileTooLargeException::class.java) {
                VaultStreams.copyWithLimit(ByteArrayInputStream(ByteArray(limit + 1)), output, limit.toLong())
            }
            assertTrue(
                "при лимите $limit записано ${output.size()}",
                output.size() <= limit
            )
        }
    }

    @Test
    fun limitIsCheckedCumulativelyAcrossChunks() {
        // Поток отдаёт по одному байту: ошибка «проверяем размер чанка, а не сумму»
        // здесь сразу видна, а на одном большом массиве — нет.
        val stream = object : ByteArrayInputStream(ByteArray(200)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, 1)
        }
        val output = ByteArrayOutputStream()
        assertThrows(VaultFileTooLargeException::class.java) {
            VaultStreams.copyWithLimit(stream, output, maxBytes = 150)
        }
        assertEquals("записано ровно до предела, без переполнения", 150, output.size())
    }

    @Test
    fun negativeLimitIsAProgrammingError() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultStreams.copyWithLimit(ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream(), -1)
        }
    }
}
