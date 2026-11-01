package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приманки honeypot: имена файлов и распознавание доступа к ним.
 *
 * Ложное срабатывание здесь — тревога на собственный файл пользователя, ложный пропуск — вредонос
 * спокойно читает «пароли» и остаётся незамеченным. Поэтому проверяется именно точность
 * сопоставления, а не «похожесть»: шаблон `^\d+_(имя)$`, а не поиск подстроки.
 */
class HoneypotManagerTest {

    private val manager = HoneypotManager()

    private fun event(timestamp: Long) = IntrusionEvent(
        id = 1,
        timestamp = timestamp,
        type = IntrusionType.HONEYPOT_ACCESSED,
        severity = IntrusionSeverity.HIGH
    )

    @Test
    fun `decoy names carry the event timestamp`() {
        val names = manager.decoyNames(event(1_700_000_000_000))

        assertEquals(3, names.size)
        assertTrue(names.all { it.startsWith("1700000000000_") })
        assertTrue(names.contains("1700000000000_passwords.txt"))
        assertTrue(names.contains("1700000000000_wallet_backup.json"))
        assertTrue(names.contains("1700000000000_private_photos"))
    }

    @Test
    fun `every generated decoy name is recognised as a decoy`() {
        manager.decoyNames(event(42)).forEach { name ->
            assertTrue("приманка «$name» не распознана", manager.isDecoyAccess(name))
            assertTrue("приманка «$name» не распознана в пути", manager.isDecoyAccess("/data/app/files/phoneguard_honeypot/$name"))
        }
    }

    @Test
    fun `decoy access is recognised in a nested path`() {
        assertTrue(manager.isDecoyAccess("/sdcard/Android/data/com.phoneguard/files/phoneguard_honeypot/1_passwords.txt"))
        assertTrue(manager.isDecoyAccess("1699999999999_wallet_backup.json"))
    }

    @Test
    fun `similar but foreign files are not decoys`() {
        assertFalse("файл без метки времени", manager.isDecoyAccess("passwords.txt"))
        assertFalse("чужое расширение", manager.isDecoyAccess("1234_passwords.txt.bak"))
        assertFalse("префикс не цифры", manager.isDecoyAccess("abc_passwords.txt"))
        assertFalse("другое имя", manager.isDecoyAccess("1234_notes.txt"))
        assertFalse("вхождение подстроки", manager.isDecoyAccess("1234_passwords.txt_evil"))
        assertFalse("регистр", manager.isDecoyAccess("1234_Passwords.txt"))
        assertFalse("пустой путь", manager.isDecoyAccess(""))
    }

    @Test
    fun `timestamp prefix is not confused with the file name`() {
        // `substringBefore('_')` в уборке берёт именно метку времени, поэтому метка обязана
        // идти в начале имени — иначе старые наборы не удалялись бы.
        val name = manager.decoyNames(event(5)).first()
        assertEquals(5L, name.substringBefore('_').toLong())
    }
}

/**
 * Каталог приманок на реальной файловой системе.
 *
 * Раньше каждый инцидент добавлял новый набор файлов и никто их не удалял — каталог рос без
 * ограничений (реальный дефект, исправленный в этой сессии). Проверяем на настоящей временной
 * папке: `Context` на JVM не создать, поэтому у `createDecoys` есть перегрузка с `File`.
 */
class HoneypotDirectoryTest {

    private val manager = HoneypotManager()

    private fun event(timestamp: Long) = IntrusionEvent(
        id = timestamp,
        timestamp = timestamp,
        type = IntrusionType.HONEYPOT_ACCESSED,
        severity = IntrusionSeverity.HIGH
    )

    private fun tempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("honeypot-test").toFile()

    @Test
    fun `decoys are written with plausible content`() {
        val dir = tempDir()
        try {
            val created = manager.createDecoys(dir, event(1_000))

            assertEquals(3, created.size)
            created.forEach { file ->
                assertTrue("файл ${file.name} не создан", file.isFile)
                assertTrue("приманка пустая: ${file.name}", file.length() > 0)
            }
            val passwords = created.first { it.name.endsWith("passwords.txt") }.readText()
            assertTrue("в приманке не должно быть служебных пометок", !passwords.contains("honeypot", ignoreCase = true))
            assertTrue("в приманке нет «пароля»", passwords.contains("password"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `only three newest decoy sets are kept`() {
        val dir = tempDir()
        try {
            (1L..5L).forEach { manager.createDecoys(dir, event(it * 1_000)) }

            val names = dir.list()!!.toList()
            assertEquals("в каталоге должно остаться ровно 3 набора", 9, names.size)
            val timestamps = names.map { it.substringBefore('_').toLong() }.distinct().sorted()
            assertEquals(listOf(3_000L, 4_000L, 5_000L), timestamps)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `repeated incident does not duplicate files`() {
        val dir = tempDir()
        try {
            manager.createDecoys(dir, event(2_000))
            manager.createDecoys(dir, event(2_000))

            assertEquals(3, dir.list()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `foreign files in the directory are not touched`() {
        val dir = tempDir()
        try {
            dir.mkdirs()
            val userFile = java.io.File(dir, "user-notes.txt").apply { writeText("не приманка") }
            val similar = java.io.File(dir, "1_passwords.txt.bak").apply { writeText("не приманка") }

            (1L..4L).forEach { manager.createDecoys(dir, event(it * 1_000)) }

            assertTrue("посторонний файл удалён", userFile.exists())
            assertTrue("похожий по имени файл удалён", similar.exists())
            assertEquals("не приманка", userFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
