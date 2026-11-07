package com.phoneguard.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Файловый слой отчётов о падениях.
 *
 * Тест работает на настоящей файловой системе (временный каталог), поэтому проверяются
 * именно те правила, которые раньше жили «на глаз»: сколько отчётов остаётся после серии
 * падений и когда обнуляется журнал сессии. Ограничение «не больше 20 отчётов» до этого
 * не проверялось ничем — падающее в цикле приложение заполняло внутреннее хранилище.
 */
class CrashReportStoreTest {

    private lateinit var dir: File
    private lateinit var store: CrashReportStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("crash-store").toFile()
        store = CrashReportStore(File(dir, "crash-reports"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `report is written with header and stack trace`() {
        val file = store.writeReport(IllegalStateException("boom"), now = 1_700_000_000_000L)

        assertNotNull(file)
        assertTrue("имя отчёта: ${file!!.name}", file.name.startsWith(CrashReportStore.CRASH_PREFIX))
        val content = file.readText()
        assertTrue(content.startsWith("PhoneGuard crash report"))
        assertTrue(content.contains("java.lang.IllegalStateException"))
        assertTrue(content.contains("boom"))
    }

    @Test
    fun `two crashes in the same second do not overwrite each other`() {
        // Раньше в имени были только секунды: два падения подряд (типичный краш-цикл)
        // оставляли один файл, и второй стек-трейс терялся.
        val first = store.writeReport(RuntimeException("first"), now = 1_700_000_000_100L)
        val second = store.writeReport(RuntimeException("second"), now = 1_700_000_000_900L)

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first!!.name, second!!.name)
        assertEquals(2, store.crashReports().size)
    }

    @Test
    fun `catalogue keeps at most twenty reports`() {
        repeat(CrashReportStore.MAX_REPORTS + 5) { index ->
            store.writeReport(RuntimeException("crash-$index"), now = 1_700_000_000_000L + index * 1000L)
        }

        assertEquals(CrashReportStore.MAX_REPORTS, store.crashReports().size)
    }

    @Test
    fun `pruning keeps the newest reports`() {
        repeat(CrashReportStore.MAX_REPORTS + 3) { index ->
            store.writeReport(RuntimeException("crash-$index"), now = 1_700_000_000_000L + index * 1000L)
        }

        val names = store.crashReports().map { it.name }
        // Самый старый (crash-0) обязан исчезнуть, самый свежий — остаться.
        assertFalse(
            "старый отчёт должен быть удалён: $names",
            names.any { it.contains(CrashReportStore.fileName(1_700_000_000_000L)) }
        )
        assertTrue(
            "свежий отчёт должен остаться: $names",
            names.contains(CrashReportStore.fileName(1_700_000_000_000L + (CrashReportStore.MAX_REPORTS + 2) * 1000L))
        )
    }

    @Test
    fun `session log appends lines`() {
        assertTrue(store.appendSessionLine("first", now = 10L))
        assertTrue(store.appendSessionLine("second", now = 20L))

        val log = File(store.ensureDirectory(), CrashReportStore.SESSION_LOG)
        assertTrue(log.exists())
        val lines = log.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("10 first"))
        assertTrue(lines[1].startsWith("20 second"))
    }

    @Test
    fun `session log is reset when the limit is exceeded`() {
        val log = File(store.ensureDirectory(), CrashReportStore.SESSION_LOG)
        // Готовим «переполненный» журнал: одна длинная строка сверх лимита.
        log.writeText("x".repeat(CrashReportStore.MAX_SESSION_LOG_BYTES + 1))

        assertTrue(store.appendSessionLine("after-rotation", now = 5L))

        val content = log.readText()
        assertTrue("старое содержимое должно исчезнуть", content.length < 200)
        assertTrue(content.contains("after-rotation"))
    }

    @Test
    fun `session log is not counted as a crash report`() {
        store.appendSessionLine("hello", now = 1L)
        store.writeReport(RuntimeException("boom"), now = 2L)

        assertEquals(1, store.crashReports().size)
        // В общем списке журнал сессии остаётся: пользователь видит его в настройках.
        assertEquals(2, store.reports().size)
    }

    @Test
    fun `clear removes every file`() {
        store.writeReport(RuntimeException("boom"), now = 1L)
        store.appendSessionLine("hello", now = 2L)

        store.clear()

        assertTrue(store.reports().isEmpty())
    }

    @Test
    fun `missing directory is created on demand`() {
        val nested = File(dir, "a/b/c")
        val other = CrashReportStore(nested)
        assertFalse(nested.exists())

        val file = other.writeReport(RuntimeException("boom"), now = 1L)

        assertNotNull(file)
        assertTrue(nested.isDirectory)
    }

    @Test
    fun `report formatting is deterministic for a fixed timestamp`() {
        // Одно и то же исключение: два `RuntimeException("boom")` из разных строк дают
        // разные стек-трейсы, и сравнение текстов проверяло бы номера строк в тесте.
        val throwable = RuntimeException("boom")
        val first = CrashReportStore.formatReport(throwable, timestamp = 0L)
        val second = CrashReportStore.formatReport(throwable, timestamp = 0L)

        assertEquals(first, second)
        // Имя класса и сообщение обязаны быть в отчёте: по ним пользователь ищет причину.
        assertTrue(first.contains("java.lang.RuntimeException"))
        assertTrue(first.contains("message: boom"))
    }

    @Test
    fun `file name embeds milliseconds`() {
        val name = CrashReportStore.fileName(1_700_000_000_123L)

        assertTrue(name, name.startsWith(CrashReportStore.CRASH_PREFIX))
        assertTrue(name, name.endsWith(".txt"))
        assertTrue("ожидались миллисекунды в имени: $name", name.contains("_123"))
    }
}
