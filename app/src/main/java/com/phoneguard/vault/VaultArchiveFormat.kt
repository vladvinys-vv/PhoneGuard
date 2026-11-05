package com.phoneguard.vault

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Переносимый архив сейфа — файл, который можно увезти на новый телефон.
 *
 * Зачем отдельный формат. Файлы сейфа зашифрованы `EncryptedFile` ключом из
 * **AndroidKeyStore**, а он не покидает устройство по замыслу: скопировать
 * `filesDir/vault` на новый телефон бессмысленно, ключа там нет. Поэтому при смене
 * телефона сейф терялся целиком — при том, что настройки к этому моменту уже
 * переносились (см. `util/SettingsTransfer`). Здесь архив шифруется ключом,
 * выведенным из **парольной фразы пользователя**, и такой файл читается где угодно.
 *
 * Криптосхема — encrypt-then-MAC, без самодеятельности:
 *
 *  * ключ: PBKDF2-HMAC-SHA256, 512 бит, соль 16 байт, число итераций хранится в заголовке;
 *  * шифр: AES-256-CTR (потоковый, поэтому файлы в 50 МБ не читаются в память);
 *  * целостность: HMAC-SHA256 по заголовку + метаданным записи + шифротексту, тег в конце записи.
 *
 * Почему CTR + HMAC, а не AES-GCM: GCM в JVM расшифровывается потоково только через
 * `CipherInputStream`, который теряет тег и молчит об этом — «битый» архив выглядел бы
 * как валидный. Здесь тег сравнивается явно, `MessageDigest.isEqual`, и подмена
 * пароля/файла даёт один и тот же честный отказ.
 *
 * Что даёт HMAC по заголовку: подмена числа итераций или соли ломает ключ, и первая же
 * запись не сойдётся по тегу; перестановка записей местами не проходит из-за индекса в MAC.
 * Число итераций при чтении ограничено: без предела файл с `iterations = 2_000_000_000`
 * заставил бы телефон считать ключ минутами (отказ в обслуживании чужим архивом).
 *
 * Модуль без Android: формат и криптография проверяются JVM-тестами целиком.
 */
object VaultArchiveFormat {

    /** Сигнатура формата: по ней отличаем свой файл от случайно выбранного. */
    const val MAGIC = "PGVAULT1"

    /** Версия формата. Читаем только свою: чужой/будущий архив не разбираем наугад. */
    const val VERSION = 1

    /** PBKDF2 по умолчанию. Значение хранится в файле, поэтому его можно поднять позже. */
    const val DEFAULT_ITERATIONS = 120_000

    /** Нижняя граница: меньше — ключ перебирается на бытовой видеокарте. */
    const val MIN_ITERATIONS = 10_000

    /** Верхняя граница: защита от «архива-тормоза» с миллиардом итераций. */
    const val MAX_ITERATIONS = 600_000

    const val SALT_BYTES = 16
    const val IV_BYTES = 16
    const val TAG_BYTES = 32
    const val KEY_BYTES = 32
    const val MAX_ENTRIES = 1000
    const val MAX_NAME_BYTES = 512
    const val MAX_MIME_BYTES = 128

    /**
     * Предел на содержимое одной записи — 50 МБ, как `EncryptionManager.MAX_FILE_SIZE_BYTES`.
     *
     * Значение продублировано осознанно: этот файл обязан оставаться чистым JVM-кодом
     * (его целиком проверяют тесты без Android), а `EncryptionManager` тянет
     * `androidx.security.crypto`. Совпадение стережёт `VaultTransferManager`: он не даёт
     * выгрузить запись больше лимита сейфа.
     */
    const val MAX_ENTRY_BYTES = 50L * 1024 * 1024

    /** Кусок шифротекста: длина каждой порции пишется в архив, поэтому размер заранее не нужен. */
    const val CHUNK_BYTES = 64 * 1024

    /** Минимальная длина парольной фразы. Короче — не фраза, а «123». */
    const val MIN_PASSPHRASE_LENGTH = 8

    const val FALLBACK_MIME = "application/octet-stream"

    private const val CIPHER = "AES/CTR/NoPadding"
    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 512

    /** Почему архив не прочитан. Коды, а не строки: тексты локализует UI. */
    enum class Rejection {
        EMPTY_PASSPHRASE,
        WEAK_PASSPHRASE,
        NOT_AN_ARCHIVE,
        UNSUPPORTED_VERSION,
        BAD_ITERATIONS,
        TOO_MANY_ENTRIES,
        EMPTY_ARCHIVE,
        BAD_NAME,
        WRONG_PASSPHRASE_OR_CORRUPT,
        CORRUPT,
        OUTPUT_UNAVAILABLE
    }

    /** Метаданные записи, которые нужны до расшифровки содержимого. */
    class Meta(val name: String, val mimeType: String, val createdAt: Long)

    /** Источник содержимого для записи в архив. */
    class Source(
        val name: String,
        val mimeType: String,
        val createdAt: Long,
        val openStream: () -> InputStream?
    )

    /**
     * Проверка парольной фразы до начала работы.
     *
     * @return причина отказа или `null`, если фраза годится
     */
    fun rejectionForPassphrase(passphrase: CharArray): Rejection? = when {
        passphrase.isEmpty() -> Rejection.EMPTY_PASSPHRASE
        passphrase.size < MIN_PASSPHRASE_LENGTH -> Rejection.WEAK_PASSPHRASE
        else -> null
    }

    /**
     * Пишет архив в [output]. Поток не закрывается: его закрывает вызывающий код.
     *
     * @return число записанных записей
     * @throws IllegalArgumentException если фраза слабая, записей слишком много или
     *   содержимое записи больше [MAX_ENTRY_BYTES]
     */
    fun write(
        output: OutputStream,
        passphrase: CharArray,
        sources: List<Source>,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
        maxEntryBytes: Long = MAX_ENTRY_BYTES
    ): Int {
        require(maxEntryBytes in 0..MAX_ENTRY_BYTES) { "entry limit out of range" }
        require(rejectionForPassphrase(passphrase) == null) { "weak passphrase" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        require(sources.isNotEmpty()) { "nothing to export" }
        require(sources.size <= MAX_ENTRIES) { "too many entries" }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val keys = deriveKeys(passphrase, salt, iterations)
        val data = DataOutputStream(BufferedOutputStream(output))

        data.write(MAGIC.toByteArray(Charsets.US_ASCII))
        data.writeInt(VERSION)
        data.writeInt(iterations)
        data.write(salt)
        data.writeInt(sources.size)

        sources.forEachIndexed { index, source ->
            writeEntry(
                data, index, source, sources.size, VERSION, iterations, salt, keys, random, maxEntryBytes
            )
        }
        data.flush()
        return sources.size
    }

    /**
     * Читает архив и отдаёт расшифрованное содержимое записи в поток, который вернёт [sink].
     *
     * **Важно про контракт:** тег записи проверяется в её конце, поэтому к моменту отказа
     * [sink] уже мог получить часть байт. Вызывающий код обязан выбрасывать всё записанное,
     * если результат — [VaultReadFailure] (`VaultTransferManager` держит распаковку в
     * отдельном каталоге и удаляет его в `finally`). Так сделано намеренно: иначе пришлось
     * бы держать в памяти весь файл до 50 МБ ради проверки тега.
     *
     * @param sink вызывается после чтения метаданных; `null` — писать некуда
     */
    fun read(
        input: InputStream,
        passphrase: CharArray,
        sink: (Meta) -> OutputStream?
    ): VaultReadResult {
        val passphraseIssue = rejectionForPassphrase(passphrase)
        if (passphraseIssue != null) return VaultReadFailure(passphraseIssue)

        val data = DataInputStream(BufferedInputStream(input))
        return try {
            val magic = ByteArray(MAGIC.length)
            try {
                data.readFully(magic)
            } catch (_: EOFException) {
                return VaultReadFailure(Rejection.NOT_AN_ARCHIVE)
            }
            if (!magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
                return VaultReadFailure(Rejection.NOT_AN_ARCHIVE)
            }

            val version = data.readInt()
            if (version != VERSION) return VaultReadFailure(Rejection.UNSUPPORTED_VERSION)

            val iterations = data.readInt()
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
                return VaultReadFailure(Rejection.BAD_ITERATIONS)
            }

            val salt = ByteArray(SALT_BYTES)
            data.readFully(salt)

            val count = data.readInt()
            if (count == 0) return VaultReadFailure(Rejection.EMPTY_ARCHIVE)
            if (count < 0 || count > MAX_ENTRIES) {
                return VaultReadFailure(Rejection.TOO_MANY_ENTRIES)
            }

            val keys = deriveKeys(passphrase, salt, iterations)
            for (index in 0 until count) {
                val failure = readEntry(
                    data, index, count, version, iterations, salt, keys, sink
                )
                if (failure != null) return VaultReadFailure(failure)
            }
            VaultReadSuccess(count)
        } catch (_: EOFException) {
            // Файл обрезан: половина записей прочитана, доверять нельзя.
            VaultReadFailure(Rejection.CORRUPT)
        } catch (_: IOException) {
            VaultReadFailure(Rejection.CORRUPT)
        } catch (_: java.security.GeneralSecurityException) {
            VaultReadFailure(Rejection.CORRUPT)
        }
    }

    /** Ключи: отдельные для шифрования и для MAC — общий ключ на две операции это ошибка схемы. */
    private class Keys(val encryption: SecretKeySpec, val authentication: SecretKeySpec)

    private fun deriveKeys(passphrase: CharArray, salt: ByteArray, iterations: Int): Keys {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val encoded = try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val encryption = encoded.copyOfRange(0, KEY_BYTES)
        val authentication = encoded.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)
        encoded.fill(0)
        return Keys(SecretKeySpec(encryption, "AES"), SecretKeySpec(authentication, MAC_ALGORITHM))
    }

    private fun writeEntry(
        data: DataOutputStream,
        index: Int,
        source: Source,
        count: Int,
        version: Int,
        iterations: Int,
        salt: ByteArray,
        keys: Keys,
        random: SecureRandom,
        maxEntryBytes: Long
    ) {
        val nameBytes = source.name.toByteArray(Charsets.UTF_8)
        require(nameBytes.isNotEmpty() && nameBytes.size <= MAX_NAME_BYTES) { "bad entry name" }
        val mimeBytes = source.mimeType.ifBlank { FALLBACK_MIME }.toByteArray(Charsets.UTF_8)
        require(mimeBytes.size <= MAX_MIME_BYTES) { "bad mime type" }

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        data.writeInt(nameBytes.size)
        data.write(nameBytes)
        data.writeInt(mimeBytes.size)
        data.write(mimeBytes)
        data.writeLong(source.createdAt)
        data.write(iv)

        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, keys.encryption, IvParameterSpec(iv))
        }
        val mac = newMac(keys.authentication)
        updatePreamble(mac, version, iterations, salt, count)
        mac.update(intBytes(index))
        mac.update(intBytes(nameBytes.size))
        mac.update(nameBytes)
        mac.update(intBytes(mimeBytes.size))
        mac.update(mimeBytes)
        mac.update(longBytes(source.createdAt))
        mac.update(iv)

        val input = source.openStream() ?: throw IllegalStateException("entry stream unavailable")
        var written = 0L
        input.use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val encrypted = cipher.update(buffer, 0, read)
                if (encrypted.isEmpty()) continue
                written += encrypted.size
                require(written <= maxEntryBytes) { "entry too large" }
                data.writeInt(encrypted.size)
                data.write(encrypted)
                mac.update(intBytes(encrypted.size))
                mac.update(encrypted)
            }
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) {
            written += tail.size
            require(written <= maxEntryBytes) { "entry too large" }
            data.writeInt(tail.size)
            data.write(tail)
            mac.update(intBytes(tail.size))
            mac.update(tail)
        }

        // Терминатор порций: читатель останавливается на нулевой длине.
        data.writeInt(0)
        mac.update(intBytes(0))
        data.write(mac.doFinal())
    }

    /** @return причина отказа или `null`, если запись прочитана и тег сошёлся */
    private fun readEntry(
        data: DataInputStream,
        index: Int,
        count: Int,
        version: Int,
        iterations: Int,
        salt: ByteArray,
        keys: Keys,
        sink: (Meta) -> OutputStream?
    ): Rejection? {
        val nameLength = data.readInt()
        if (nameLength <= 0 || nameLength > MAX_NAME_BYTES) return Rejection.BAD_NAME
        val nameBytes = ByteArray(nameLength)
        data.readFully(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)
        if (!isValidName(name)) return Rejection.BAD_NAME

        val mimeLength = data.readInt()
        if (mimeLength < 0 || mimeLength > MAX_MIME_BYTES) return Rejection.CORRUPT
        val mimeBytes = ByteArray(mimeLength)
        data.readFully(mimeBytes)
        val mimeType = String(mimeBytes, Charsets.UTF_8).ifBlank { FALLBACK_MIME }

        val createdAt = data.readLong()
        val iv = ByteArray(IV_BYTES)
        data.readFully(iv)

        val output = sink(Meta(name, mimeType, createdAt))
            ?: return Rejection.OUTPUT_UNAVAILABLE

        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.DECRYPT_MODE, keys.encryption, IvParameterSpec(iv))
        }
        val mac = newMac(keys.authentication)
        updatePreamble(mac, version, iterations, salt, count)
        mac.update(intBytes(index))
        mac.update(intBytes(nameBytes.size))
        mac.update(nameBytes)
        mac.update(intBytes(mimeBytes.size))
        mac.update(mimeBytes)
        mac.update(longBytes(createdAt))
        mac.update(iv)

        var written = 0L
        try {
            output.use { stream ->
                while (true) {
                    val chunkLength = data.readInt()
                    if (chunkLength == 0) break
                    if (chunkLength < 0 || chunkLength > CHUNK_BYTES) return Rejection.CORRUPT
                    val chunk = ByteArray(chunkLength)
                    data.readFully(chunk)
                    written += chunkLength
                    if (written > MAX_ENTRY_BYTES) return Rejection.CORRUPT
                    mac.update(intBytes(chunkLength))
                    mac.update(chunk)
                    stream.write(cipher.update(chunk))
                }
                stream.write(cipher.doFinal())
                stream.flush()
            }
        } catch (_: IOException) {
            return Rejection.CORRUPT
        }

        mac.update(intBytes(0))
        val expected = data.readFully(TAG_BYTES)
        // Сравнение постоянного времени: побайтовый `contentEquals` на теге — это oracle.
        if (!MessageDigest.isEqual(mac.doFinal(), expected)) {
            return Rejection.WRONG_PASSPHRASE_OR_CORRUPT
        }
        return null
    }

    /**
     * Имя записи должно быть безопасным до того, как оно станет путём в каталоге сейфа.
     * Те же правила, что в [VaultFileNames.sanitize], но здесь отказ, а не молчаливая чистка:
     * подменённое имя — признак испорченного архива.
     */
    fun isValidName(name: String): Boolean {
        if (name.isBlank() || name.length > VaultFileNames.MAX_NAME_LENGTH) return false
        if (name == "." || name == "..") return false
        if (name.startsWith(".")) return false
        return name.none { it == '/' || it == '\\' || it.isISOControl() }
    }

    private fun newMac(key: SecretKeySpec): Mac =
        Mac.getInstance(MAC_ALGORITHM).apply { init(key) }

    private fun updatePreamble(mac: Mac, version: Int, iterations: Int, salt: ByteArray, count: Int) {
        mac.update(MAGIC.toByteArray(Charsets.US_ASCII))
        mac.update(intBytes(version))
        mac.update(intBytes(iterations))
        mac.update(salt)
        mac.update(intBytes(count))
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()

    /** Читает ровно [size] байт: `read` может вернуть меньше, чем просили. */
    private fun DataInputStream.readFully(size: Int): ByteArray {
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes
    }
}

/** Итог чтения архива. */
sealed class VaultReadResult

/** Архив прочитан и расшифрован целиком. */
data class VaultReadSuccess(val entries: Int) : VaultReadResult()

/** Архив не принят: см. [VaultArchiveFormat.Rejection]. */
data class VaultReadFailure(val reason: VaultArchiveFormat.Rejection) : VaultReadResult()
