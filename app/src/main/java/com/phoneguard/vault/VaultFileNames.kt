package com.phoneguard.vault

import java.io.File

/**
 * Правила имён и путей для файлов сейфа — без Android-зависимостей.
 *
 * Вынесено из [EncryptionManager], потому что здесь живут проверки безопасности:
 * имя файла приходит из БД и из `ContentResolver` (то есть фактически извне), а
 * расшифрованная копия удаляется по пути, который передаёт вызывающий код.
 * Ошибка в этих трёх функциях — это либо выход за пределы каталога сейфа
 * (`../../databases/…`), либо удаление чужого файла. Теперь их можно проверить
 * JVM-тестами.
 */
internal object VaultFileNames {

    /** Предел длины имени: `ext4` держит 255 байт, длинные имена ломают `File.createTempFile`. */
    const val MAX_NAME_LENGTH = 100

    /** Символы, недопустимые в имени файла на FAT/Windows-совместимых ФС и в `file://`-URI. */
    private val FORBIDDEN_CHARS = setOf(':', '*', '?', '"', '<', '>', '|', '\u0000')

    /**
     * Оставляет буквы любого алфавита, цифры, точку, дефис и подчёркивание.
     *
     * Отбрасываются только разделители пути и управляющие символы — имена
     * пользовательских файлов не обязаны быть латиницей. Ведущие точки убираются,
     * чтобы имя не превращалось в «скрытый» файл или `.`/`..`.
     *
     * @param fallback имя, если после очистки ничего не осталось
     */
    fun sanitize(name: String, fallback: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .map { ch -> if (ch.isISOControl() || ch in FORBIDDEN_CHARS) '_' else ch }
            .joinToString("")
            .trim()
            .trimStart('.')
            .take(MAX_NAME_LENGTH)
        return cleaned.ifBlank { fallback }
    }

    /**
     * Подбирает свободное имя: `photo.jpg` → `photo(1).jpg`.
     *
     * @param exists проверка существования имени (в проде — `File(vaultDir, name).exists()`)
     */
    fun unique(fileName: String, exists: (String) -> Boolean): String {
        if (!exists(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$base($counter)$extension"
            if (!exists(candidate)) return candidate
            counter++
        }
    }

    /**
     * Лежит ли [targetPath] внутри [rootPath] (обе строки — канонические пути).
     *
     * Сравнение по префиксу с обязательным разделителем: иначе каталог-сосед
     * `/data/vault2` считался бы «внутри» `/data/vault`, и `delete()` ушёл бы в
     * чужой каталог.
     */
    fun isInside(rootPath: String, targetPath: String): Boolean =
        targetPath.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
}
