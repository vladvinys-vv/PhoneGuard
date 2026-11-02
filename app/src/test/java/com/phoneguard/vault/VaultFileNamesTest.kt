package com.phoneguard.vault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имена и пути файлов сейфа.
 *
 * Имя приходит из `ContentResolver` (то есть от произвольного приложения-источника),
 * а расшифрованная копия удаляется по пути от вызывающего кода — ошибка здесь означает
 * выход за каталог сейфа или удаление чужих данных.
 */
class VaultFileNamesTest {

    @Test
    fun `разделители пути не проходят в имя файла`() {
        assertEquals("passwd", VaultFileNames.sanitize("../../etc/passwd", "fallback"))
        assertEquals("passwd", VaultFileNames.sanitize("..\\..\\windows\\passwd", "fallback"))
        assertEquals("photo.jpg", VaultFileNames.sanitize("C:\\photos\\photo.jpg", "fallback"))
    }

    @Test
    fun `запрещённые символы заменяются подчёркиванием`() {
        assertEquals("a_b_c_d_e_f_g_h", VaultFileNames.sanitize("a:b*c?d\"e<f>g|h", "fallback"))
    }

    @Test
    fun `управляющие символы вычищаются`() {
        assertEquals("ab_cd", VaultFileNames.sanitize("ab\u0000cd", "fallback"))
        assertEquals("ab_cd", VaultFileNames.sanitize("ab\ncd", "fallback"))
    }

    @Test
    fun `буквы других алфавитов сохраняются`() {
        assertEquals("Договор_2024.pdf", VaultFileNames.sanitize("Договор_2024.pdf", "fallback"))
        assertEquals("Übergabe.pdf", VaultFileNames.sanitize("Übergabe.pdf", "fallback"))
    }

    @Test
    fun `ведущие точки убираются`() {
        assertEquals("bashrc", VaultFileNames.sanitize(".bashrc", "fallback"))
        assertEquals("hidden.txt", VaultFileNames.sanitize("..hidden.txt", "fallback"))
        // Голые `.` и `..` — это уже не имя файла.
        assertEquals("fallback", VaultFileNames.sanitize("..", "fallback"))
        assertEquals("fallback", VaultFileNames.sanitize(".", "fallback"))
    }

    @Test
    fun `слишком длинное имя обрезается`() {
        val sanitized = VaultFileNames.sanitize("a".repeat(500) + ".jpg", "fallback")
        assertEquals(VaultFileNames.MAX_NAME_LENGTH, sanitized.length)
    }

    @Test
    fun `пустое имя заменяется запасным`() {
        assertEquals("fallback", VaultFileNames.sanitize("", "fallback"))
        assertEquals("fallback", VaultFileNames.sanitize("   ", "fallback"))
        assertEquals("fallback", VaultFileNames.sanitize("///", "fallback"))
    }

    @Test
    fun `свободное имя не меняется`() {
        assertEquals("photo.jpg", VaultFileNames.unique("photo.jpg") { false })
    }

    @Test
    fun `занятое имя получает суффикс перед расширением`() {
        val taken = mutableSetOf("photo.jpg", "photo(1).jpg")
        assertEquals("photo(2).jpg", VaultFileNames.unique("photo.jpg") { it in taken })
    }

    @Test
    fun `имя без расширения тоже получает суффикс`() {
        val taken = mutableSetOf("backup")
        assertEquals("backup(1)", VaultFileNames.unique("backup") { it in taken })
    }

    @Test
    fun `скрытые файлы и файлы с точкой в начале считаются без расширения`() {
        // `.env` — точка в индексе 0, поэтому это имя целиком, а не расширение.
        assertEquals(".env(1)", VaultFileNames.unique(".env") { it == ".env" })
    }

    @Test
    fun `вложенные каталоги сейфа считаются внутри`() {
        val root = File("/data/user/0/com.phoneguard/files/vault").canonicalPath
        assertTrue(VaultFileNames.isInside(root, File(root, "photo.jpg").path))
        assertTrue(VaultFileNames.isInside(root, File(root, "sub/photo.jpg").path))
    }

    @Test
    fun `каталог-сосед с общим префиксом не считается внутри`() {
        val root = File("/data/user/0/com.phoneguard/files/vault").canonicalPath
        assertFalse(VaultFileNames.isInside(root, "/data/user/0/com.phoneguard/files/vault2/photo.jpg"))
        assertFalse(VaultFileNames.isInside(root, "/data/user/0/com.phoneguard/files/photo.jpg"))
        assertFalse(VaultFileNames.isInside(root, "/data/user/0/com.phoneguard/files/vault"))
    }

    @Test
    fun `выход из каталога через две точки не считается внутри`() {
        val root = File("/data/user/0/com.phoneguard/files/vault").canonicalPath
        val escape = File(root, "../../databases/phoneguard.db").canonicalPath
        assertFalse(VaultFileNames.isInside(root, escape))
    }
}
