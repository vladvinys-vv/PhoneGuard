package com.phoneguard.vault

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Переносимый архив сейфа: формат, криптография и отказы.
 *
 * Проверять здесь важнее отказы, чем успех: успешный архив пользователь увидит один раз
 * при смене телефона, а «тихо прочитанный мусор» — это либо потерянные файлы, либо
 * открытый в чужой парольной фразе архив.
 */
class VaultArchiveFormatTest {

    /** Малое число итераций: тест проверяет логику, а не скорость PBKDF2. */
    private val iterations = VaultArchiveFormat.MIN_ITERATIONS

    private val passphrase = "correct horse battery".toCharArray()

    private fun source(
        name: String,
        content: ByteArray,
        mimeType: String = "application/pdf",
        createdAt: Long = 1_760_000_000_000L
    ) = VaultArchiveFormat.Source(name, mimeType, createdAt) { content.inputStream() }

    private fun writeArchive(
        sources: List<VaultArchiveFormat.Source>,
        phrase: CharArray = passphrase
    ): ByteArray {
        val out = ByteArrayOutputStream()
        VaultArchiveFormat.write(out, phrase, sources, iterations = iterations)
        return out.toByteArray()
    }

    /** Расшифровывает и складывает содержимое в память (в проде — файлы в сейфе). */
    private fun readArchive(
        bytes: ByteArray,
        phrase: CharArray = passphrase
    ): Pair<VaultReadResult, List<Triple<String, String, ByteArray>>> {
        val outputs = mutableListOf<Pair<VaultArchiveFormat.Meta, ByteArrayOutputStream>>()
        val result = VaultArchiveFormat.read(bytes.inputStream(), phrase) { meta ->
            ByteArrayOutputStream().also { outputs.add(meta to it) }
        }
        val entries = outputs.map { (meta, out) ->
            Triple(meta.name, meta.mimeType, out.toByteArray())
        }
        return result to entries
    }

    @Test
    fun `roundtrip keeps name mime date and content`() {
        val content = "sehr geheimes Dokument".toByteArray()
        val bytes = writeArchive(listOf(source("Vertrag.pdf", content, createdAt = 123L)))
        val (result, entries) = readArchive(bytes)

        assertEquals(VaultReadSuccess(1), result)
        assertEquals(1, entries.size)
        assertEquals("Vertrag.pdf", entries[0].first)
        assertEquals("application/pdf", entries[0].second)
        assertArrayEquals(content, entries[0].third)
    }

    @Test
    fun `several entries keep their order`() {
        val bytes = writeArchive(
            listOf(
                source("first.txt", "one".toByteArray(), mimeType = "text/plain"),
                source("second.txt", "two".toByteArray(), mimeType = "text/plain")
            )
        )
        val (result, entries) = readArchive(bytes)

        assertEquals(VaultReadSuccess(2), result)
        assertEquals(listOf("first.txt", "second.txt"), entries.map { it.first })
    }

    @Test
    fun `content larger than a chunk survives`() {
        // 200 КБ — три порции по 64 КБ: проверяет и нарезку, и склейку.
        val content = ByteArray(200_000) { (it % 251).toByte() }
        val bytes = writeArchive(listOf(source("big.bin", content, mimeType = "application/octet-stream")))
        val (result, entries) = readArchive(bytes)

        assertEquals(VaultReadSuccess(1), result)
        assertArrayEquals(content, entries.single().third)
    }

    @Test
    fun `empty file is a valid entry`() {
        val bytes = writeArchive(listOf(source("empty.txt", ByteArray(0), mimeType = "text/plain")))
        val (result, entries) = readArchive(bytes)

        assertEquals(VaultReadSuccess(1), result)
        assertEquals(0, entries.single().third.size)
    }

    @Test
    fun `wrong passphrase is refused and yields no usable content`() {
        val secret = "sehr geheimes Dokument".toByteArray()
        val bytes = writeArchive(listOf(source("a.txt", secret)))
        val (result, entries) = readArchive(bytes, "wrong horse battery".toCharArray())

        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.WRONG_PASSPHRASE_OR_CORRUPT), result)
        // Байты могли уйти в поток до проверки тега — но это не открытый текст,
        // и менеджер импорта обязан выбросить распакованное при отказе.
        entries.forEach { entry ->
            assertFalse("открытый текст не должен совпасть", secret.contentEquals(entry.third))
        }
    }

    @Test
    fun `flipped ciphertext byte is refused`() {
        val bytes = writeArchive(listOf(source("a.txt", ByteArray(100) { 7 })))
        // Байт внутри шифротекста: тег в конце записи (32 байта) не тронут.
        bytes[bytes.size - 40] = (bytes[bytes.size - 40] + 1).toByte()
        val (result, _) = readArchive(bytes)

        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.WRONG_PASSPHRASE_OR_CORRUPT), result)
    }

    @Test
    fun `changed iteration count breaks the archive instead of being trusted`() {
        val bytes = writeArchive(listOf(source("a.txt", "x".toByteArray())))
        // 8 байт сигнатуры + 4 версии, дальше число итераций.
        val patched = bytes.copyOf()
        ByteBuffer.wrap(patched, 12, 4).putInt(iterations + 1)
        val (result, _) = readArchive(patched)

        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.WRONG_PASSPHRASE_OR_CORRUPT), result)
    }

    @Test
    fun `short and empty passphrases are rejected before any work`() {
        assertEquals(
            VaultArchiveFormat.Rejection.EMPTY_PASSPHRASE,
            VaultArchiveFormat.rejectionForPassphrase(CharArray(0))
        )
        assertEquals(
            VaultArchiveFormat.Rejection.WEAK_PASSPHRASE,
            VaultArchiveFormat.rejectionForPassphrase("1234".toCharArray())
        )
        assertNull(VaultArchiveFormat.rejectionForPassphrase(passphrase))

        val (result, _) = readArchive(writeArchive(listOf(source("a.txt", "x".toByteArray()))), "short".toCharArray())
        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.WEAK_PASSPHRASE), result)
    }

    @Test
    fun `foreign file is not an archive`() {
        val (result, _) = readArchive("just a text file, not an archive".toByteArray())
        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.NOT_AN_ARCHIVE), result)
    }

    @Test
    fun `truncated file is refused as corrupt`() {
        val bytes = writeArchive(listOf(source("a.txt", "0123456789".toByteArray())))
        val (result, _) = readArchive(bytes.copyOfRange(0, bytes.size - 10))
        // Обрезан тег последней записи: это не «неверный пароль», а битый файл.
        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.CORRUPT), result)
    }

    @Test
    fun `future version is refused`() {
        val bytes = writeArchive(listOf(source("a.txt", "x".toByteArray())))
        val patched = bytes.copyOf()
        ByteBuffer.wrap(patched, 8, 4).putInt(VaultArchiveFormat.VERSION + 1)
        val (result, _) = readArchive(patched)
        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.UNSUPPORTED_VERSION), result)
    }

    @Test
    fun `absurd iteration count is refused without hashing`() {
        // Заголовок собирается вручную: файл с миллиардом итераций иначе занял бы телефон.
        val header = archiveHeader(iterations = 2_000_000_000, entries = 1)
        val (result, _) = readArchive(header)
        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.BAD_ITERATIONS), result)
    }

    @Test
    fun `empty and oversized entry counts are refused`() {
        val emptyHeader = archiveHeader(iterations = iterations, entries = 0)
        assertEquals(
            VaultReadFailure(VaultArchiveFormat.Rejection.EMPTY_ARCHIVE),
            readArchive(emptyHeader).first
        )

        val manyHeader = archiveHeader(
            iterations = iterations,
            entries = VaultArchiveFormat.MAX_ENTRIES + 1
        )
        assertEquals(
            VaultReadFailure(VaultArchiveFormat.Rejection.TOO_MANY_ENTRIES),
            readArchive(manyHeader).first
        )
    }

    @Test
    fun `path in entry name is refused`() {
        // Свой же архив, но с враждебным именем: импорт не должен писать вне каталога сейфа.
        val bytes = writeArchive(listOf(source("../databases/phoneguard.db", "x".toByteArray())))
        val (result, _) = readArchive(bytes)
        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.BAD_NAME), result)
    }

    @Test
    fun `name validation matches the rules of the vault`() {
        assertTrue(VaultArchiveFormat.isValidName("photo 2026.jpg"))
        assertTrue(VaultArchiveFormat.isValidName("Договор аренды.pdf"))
        assertFalse(VaultArchiveFormat.isValidName(""))
        assertFalse(VaultArchiveFormat.isValidName(".."))
        assertFalse(VaultArchiveFormat.isValidName(".hidden"))
        assertFalse(VaultArchiveFormat.isValidName("a/b.txt"))
        assertFalse(VaultArchiveFormat.isValidName("a\\b.txt"))
        assertFalse(VaultArchiveFormat.isValidName("a\u0000b.txt"))
        assertFalse(VaultArchiveFormat.isValidName("x".repeat(VaultArchiveFormat.MAX_NAME_BYTES)))
    }

    @Test
    fun `closed output leaks nothing about the tag`() {
        // Нет места на диске — импорт обязан сказать об этом, а не молча пропустить запись.
        val bytes = writeArchive(listOf(source("a.txt", "x".toByteArray())))
        val result = VaultArchiveFormat.read(bytes.inputStream(), passphrase) { null }
        assertEquals(VaultReadFailure(VaultArchiveFormat.Rejection.OUTPUT_UNAVAILABLE), result)
    }

    @Test
    fun `read stream does not need to be closed by the format`() {
        // Поток закрывает вызывающий код (в проде — `use`): формат не должен закрывать чужой
        // поток, иначе менеджер не сможет отличить свою ошибку от закрытого URI.
        val bytes = writeArchive(listOf(source("a.txt", "hello".toByteArray())))
        val counting = object : InputStream() {
            private val delegate = bytes.inputStream()
            var closed = false
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
            override fun close() {
                closed = true
                delegate.close()
            }
        }
        val result = VaultArchiveFormat.read(counting, passphrase) { ByteArrayOutputStream() }
        assertEquals(VaultReadSuccess(1), result)
        assertFalse(counting.closed)
    }

    @Test
    fun `write refuses an empty list`() {
        var failed = false
        try {
            VaultArchiveFormat.write(ByteArrayOutputStream(), passphrase, emptyList(), iterations)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `entry larger than the limit is refused instead of truncated`() {
        // Предел подставлен маленьким: проверяется поведение, а не 50 МБ в памяти.
        var failed = false
        try {
            val out = ByteArrayOutputStream()
            VaultArchiveFormat.write(
                out,
                passphrase,
                listOf(source("big.bin", ByteArray(4096))),
                iterations = iterations,
                maxEntryBytes = 1024
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun archiveHeader(iterations: Int, entries: Int): ByteArray {
        val salt = ByteArray(VaultArchiveFormat.SALT_BYTES)
        val buffer = ByteArrayOutputStream()
        buffer.write(VaultArchiveFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        buffer.write(intBytes(VaultArchiveFormat.VERSION))
        buffer.write(intBytes(iterations))
        buffer.write(salt)
        buffer.write(intBytes(entries))
        return buffer.toByteArray()
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
