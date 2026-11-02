package com.phoneguard.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Правила удаления старых данных — самое опасное место в приложении: путь к файлу-улике
 * приходит из базы, и ошибка здесь означает удаление чужого файла на устройстве.
 *
 * Раньше это проверялось только через `DataRetentionManager` на MockK, то есть **не
 * исполнялось** в JVM ни разу. Теперь правила чистые ([RetentionPolicy]) и проверяются
 * на настоящих временных каталогах.
 */
class RetentionPolicyTest {

    private lateinit var root: File
    private lateinit var outside: File
    private val extraDirs = mutableListOf<File>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("phoneguard-files").toFile()
        outside = Files.createTempDirectory("phoneguard-outside").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        outside.deleteRecursively()
        extraDirs.forEach { dir -> dir.deleteRecursively() }
        extraDirs.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { extraDirs += it }

    private fun touch(dir: File, name: String): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeText("x") }

    // --- срок хранения ------------------------------------------------------

    @Test
    fun retentionIsNinetyDaysInMilliseconds() {
        assertEquals(90L * 24 * 60 * 60 * 1000, RetentionPolicy.RETENTION_MS)
        assertEquals("множители не должны переполнять Int", 7_776_000_000L, RetentionPolicy.RETENTION_MS)
    }

    @Test
    fun cutoffIsNowMinusRetention() {
        val now = 1_700_000_000_000L
        assertEquals(now - RetentionPolicy.RETENTION_MS, RetentionPolicy.cutoff(now))
    }

    @Test
    fun zeroTimestampIsNeverStale() {
        // File.lastModified() возвращает 0 при ошибке чтения: удалять по такому
        // значению нельзя — это «неизвестно», а не «очень старый».
        assertFalse(RetentionPolicy.isStale(0, cutoff = 1000))
        assertFalse(RetentionPolicy.isStale(-5, cutoff = 1000))
    }

    @Test
    fun onlyTimestampsOlderThanCutoffAreStale() {
        assertTrue(RetentionPolicy.isStale(1, cutoff = 1000))
        assertTrue(RetentionPolicy.isStale(999, cutoff = 1000))
        assertFalse("файл с временем ровно на границе ещё не старый", RetentionPolicy.isStale(1000, cutoff = 1000))
        assertFalse(RetentionPolicy.isStale(1001, cutoff = 1000))
    }

    // --- какие файлы разрешено удалять -------------------------------------

    @Test
    fun fileInsideAppFilesIsOwned() {
        val file = touch(root, "evidence.jpg")
        assertTrue(RetentionPolicy.isOwnedByApp(file, listOf(root)))
    }

    @Test
    fun fileInNestedDirectoryIsOwned() {
        val file = touch(File(root, "stealth/photos"), "evidence.jpg")
        assertTrue(RetentionPolicy.isOwnedByApp(file, listOf(root)))
    }

    @Test
    fun fileOutsideAppDirectoriesIsRejected() {
        val file = touch(outside, "DCIM.jpg")
        assertFalse(RetentionPolicy.isOwnedByApp(file, listOf(root)))
    }

    @Test
    fun siblingWithSamePrefixIsRejected() {
        // `/data/.../files-evil` начинается с `/data/.../files`, но внутри приложения не лежит:
        // именно для этого в сравнении участвует разделитель пути.
        val evil = touch(File(root.parentFile, "${root.name}-evil"), "payload.bin")
        assertFalse(RetentionPolicy.isOwnedByApp(evil, listOf(root)))
    }

    @Test
    fun traversalOutsideRootIsRejected() {
        touch(outside, "secret.txt")
        val traversal = File(root, "../${outside.name}/secret.txt")
        assertFalse(RetentionPolicy.isOwnedByApp(traversal, listOf(root)))
    }

    @Test
    fun symbolicLinkEscapingRootIsRejected() {
        // Симлинк внутри приватного каталога указывает наружу: канонический путь
        // резолвится в реальную цель, поэтому такой файл удалять нельзя.
        val target = touch(outside, "host.txt")
        val link = File(root, "link.txt")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertFalse(RetentionPolicy.isOwnedByApp(link, listOf(root)))
    }

    @Test
    fun rootItselfIsNotAFileToDelete() {
        // Защита от «путь указывает на сам каталог»: `delete()` на каталоге с содержимым
        // вернул бы false, но проверка все равно должна отсекать корень.
        assertFalse(RetentionPolicy.isOwnedByApp(root, listOf(root)))
    }

    @Test
    fun nullRootsAreSkipped() {
        val file = touch(root, "evidence.jpg")
        assertTrue(RetentionPolicy.isOwnedByApp(file, listOf(null, root)))
        assertFalse(RetentionPolicy.isOwnedByApp(file, listOf(null)))
    }

    @Test
    fun anyOfSeveralRootsIsEnough() {
        // У приложения три корня: filesDir, cacheDir и внешний каталог — файл из любого годится.
        // Уборка — в tearDown: сочетание @Test и try/finally не разбирает tree-sitter-грамматика
        // Kotlin (проверка `kotlin_analysis.py` видит там синтаксическую ошибку).
        val external = tempDir("phoneguard-external")
        val cache = touch(root, "cache.bin")
        val externalFile = touch(external, "photo.jpg")
        assertTrue(RetentionPolicy.isOwnedByApp(cache, listOf(root, external)))
        assertTrue(RetentionPolicy.isOwnedByApp(externalFile, listOf(root, external)))
    }
}
