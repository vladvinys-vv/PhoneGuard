package com.phoneguard.vault

import java.io.IOException

/**
 * Файл больше лимита сейфа.
 *
 * Отдельный тип нужен, чтобы UI показал «файл слишком большой», а не сырое
 * сообщение исключения (раньше туда попадала русская строка из кода, и в
 * английской/немецкой локали ошибка была на русском).
 *
 * Живёт в своём файле: `EncryptionManager` тянет Android, а лимит проверяется в чистом
 * [VaultStreams] — иначе его тест не собирался бы в JVM-прогоне.
 */
class VaultFileTooLargeException : IOException("Vault entry exceeds the size limit")
