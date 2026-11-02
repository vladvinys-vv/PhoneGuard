package com.phoneguard.vault

import java.io.InputStream
import java.io.OutputStream

/**
 * Копирование файла в сейф с жёстким лимитом размера.
 *
 * Вынесено из [EncryptionManager]: метод жил внутри `withContext(IO)` рядом с `ContentResolver`
 * и `EncryptedFile`, поэтому «лимит 50 МБ не обходится» не проверялось ни разу. А это ровно тот
 * случай, когда ошибка на единицу означает «сейф забит одним файлом на несколько гигабайт».
 */
internal object VaultStreams {

    /**
     * Копирует [input] в [output], возвращая число записанных байт.
     *
     * @throws VaultFileTooLargeException если данных больше [maxBytes]. Проверка идёт **до**
     *   записи очередного чанка, поэтому в выходном потоке остаются только целые чанки в пределах
     *   лимита: записанное никогда не превышает [maxBytes], а чанк, пересекающий границу, не
     *   попадает в файл вовсе. Вызывающий код всё равно обязан удалить недописанный файл сейфа
     *   (см. `EncryptionManager.encryptFileToVault`), иначе в сейфе останется обрезанный файл.
     */
    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw VaultFileTooLargeException()
            output.write(buffer, 0, read)
        }
        return total
    }
}
