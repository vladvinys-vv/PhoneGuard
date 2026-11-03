package com.phoneguard.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сколько расшифрованных копий сейфа лежит в кэше и когда их удалять.
 *
 * В кэше — **открытый текст** секретов пользователя, поэтому здесь проверяется не «удобство»,
 * а две вещи сразу: копии не копятся и при этом не удаляется файл, который просмотрщик уже
 * открыл. Правила одинаково важны в обе стороны: лишняя уборка ломает просмотр, недостаточная
 * оставляет секреты в кэше до следующей расшифровки.
 */
class VaultPreviewPolicyTest {

    private val now = 10_000_000L
    private fun ago(ms: Long) = now - ms

    private fun entry(name: String, ageMs: Long) = VaultPreviewPolicy.Entry(name, ago(ageMs))

    private fun deletedNames(entries: List<VaultPreviewPolicy.Entry>, keep: String? = null): Set<String> =
        VaultPreviewPolicy.toDelete(entries, now, keep).map { it.name }.toSet()

    @Test
    fun emptyDirectoryNothingToDo() {
        assertTrue(VaultPreviewPolicy.toDelete(emptyList(), now).isEmpty())
    }

    @Test
    fun singleFreshPreviewIsKept() {
        val entries = listOf(entry("decrypted_1.tmp", 5_000L))
        assertTrue(deletedNames(entries, keep = "decrypted_1.tmp").isEmpty())
    }

    @Test
    fun expiredPreviewIsDeleted() {
        val entries = listOf(entry("decrypted_old.tmp", VaultPreviewPolicy.PREVIEW_MAX_AGE_MS))
        assertEquals(setOf("decrypted_old.tmp"), deletedNames(entries))
    }

    @Test
    fun justYoungerThanMaxAgeIsKept() {
        val entries = listOf(entry("decrypted_young.tmp", VaultPreviewPolicy.PREVIEW_MAX_AGE_MS - 1))
        assertTrue(deletedNames(entries).isEmpty())
    }

    @Test
    fun entriesBeyondLimitAreDeletedOldestFirst() {
        // Шесть копий старше grace, но моложе maxAge: лимит 4 — удаляются две самые старые.
        val entries = listOf(
            entry("a.tmp", 5 * 60_000L),
            entry("b.tmp", 4 * 60_000L),
            entry("c.tmp", 3 * 60_000L),
            entry("d.tmp", 2 * 60_000L),
            entry("e.tmp", 90_000L),
            entry("f.tmp", 70_000L)
        )
        assertEquals(setOf("a.tmp", "b.tmp"), deletedNames(entries))
    }

    @Test
    fun protectedPreviewOccupiesOneOfTheSlots() {
        // Пять копий плюс только что созданная: разрешено 3 «старых» плюс защищённая.
        val entries = listOf(
            entry("old1.tmp", 5 * 60_000L),
            entry("old2.tmp", 4 * 60_000L),
            entry("old3.tmp", 3 * 60_000L),
            entry("old4.tmp", 2 * 60_000L),
            entry("old5.tmp", 90_000L),
            entry("fresh.tmp", 0L)
        )
        assertEquals(setOf("old1.tmp", "old2.tmp"), deletedNames(entries, keep = "fresh.tmp"))
    }

    @Test
    fun protectedPreviewIsNeverDeletedEvenWhenExpired() {
        val entries = listOf(
            entry("ancient.tmp", VaultPreviewPolicy.PREVIEW_MAX_AGE_MS * 3),
            entry("fresh.tmp", 0L)
        )
        assertFalse("fresh.tmp" in deletedNames(entries, keep = "fresh.tmp"))
        assertTrue("ancient.tmp" in deletedNames(entries, keep = "fresh.tmp"))
    }

    @Test
    fun veryNewPreviewsSurviveExceedingLimit() {
        // Пользователь листает картинки: копии моложе grace удалять нельзя, иначе просмотрщик
        // получит «файл исчез» в момент переключения.
        val entries = (1..10).map { idx -> entry("view$idx.tmp", idx * 3_000L) }
        assertTrue(deletedNames(entries).isEmpty())
    }

    @Test
    fun unknownTimestampCountsAsOldestForEviction() {
        // `lastModified == 0` — «время неизвестно»: по возрасту не удаляем, но при переполнении
        // лимита такая копия первая (считать её новейшей было бы неверно).
        val entries = listOf(
            VaultPreviewPolicy.Entry("unknown.tmp", 0L),
            entry("x1.tmp", 5 * 60_000L),
            entry("x2.tmp", 4 * 60_000L),
            entry("x3.tmp", 3 * 60_000L),
            entry("x4.tmp", 2 * 60_000L)
        )
        assertTrue("unknown.tmp" in deletedNames(entries))
    }

    @Test
    fun unknownTimestampAloneIsNotDeletedByAge() {
        val entries = listOf(VaultPreviewPolicy.Entry("unknown.tmp", 0L))
        assertTrue(deletedNames(entries).isEmpty())
    }

    @Test
    fun futureTimestampIsTreatedAsNewest() {
        // Часы устройства ушли назад: файл выглядит «из будущего». Удалять его по возрасту нельзя,
        // а в лимите он должен считаться самым свежим.
        val entries = listOf(
            VaultPreviewPolicy.Entry("future.tmp", now + 60_000L),
            entry("m1.tmp", 5 * 60_000L),
            entry("m2.tmp", 4 * 60_000L),
            entry("m3.tmp", 3 * 60_000L),
            entry("m4.tmp", 2 * 60_000L)
        )
        val deleted = deletedNames(entries)
        assertFalse("future.tmp" in deleted)
        assertEquals(1, deleted.size)
    }

    @Test
    fun equalTimestampsAreOrderedByName() {
        // Порядок должен быть детерминированным, иначе тест (и поведение) «мигают».
        val entries = listOf(
            entry("bb.tmp", 2 * 60_000L),
            entry("aa.tmp", 2 * 60_000L),
            entry("cc.tmp", 2 * 60_000L),
            entry("dd.tmp", 2 * 60_000L),
            entry("ee.tmp", 2 * 60_000L)
        )
        val deleted = deletedNames(entries)
        assertEquals(setOf("ee.tmp"), deleted)
    }

    @Test
    fun expiredAndOverLimitAreCombinedWithoutDuplicates() {
        val entries = listOf(
            entry("expired1.tmp", VaultPreviewPolicy.PREVIEW_MAX_AGE_MS * 2),
            entry("expired2.tmp", VaultPreviewPolicy.PREVIEW_MAX_AGE_MS),
            entry("old1.tmp", 5 * 60_000L),
            entry("old2.tmp", 4 * 60_000L),
            entry("old3.tmp", 3 * 60_000L),
            entry("old4.tmp", 2 * 60_000L),
            entry("old5.tmp", 90_000L)
        )
        val deleted = VaultPreviewPolicy.toDelete(entries, now).map { it.name }
        assertEquals(deleted.size, deleted.toSet().size)
        assertTrue(deleted.containsAll(setOf("expired1.tmp", "expired2.tmp")))
        // Просроченные уже удалены, из остальных пяти по лимиту уходит один самый старый.
        assertTrue("old1.tmp" in deleted)
        assertFalse("old5.tmp" in deleted)
    }

    @Test
    fun limitHoldsAfterRepeatedDecryptions() {
        // Имитация десяти последовательных предпросмотров: в каталоге не должно остаться
        // больше MAX_PREVIEW_FILES копий (все старше grace, чтобы лимит вообще срабатывал).
        var entries = emptyList<VaultPreviewPolicy.Entry>()
        var clock = now
        repeat(10) { index ->
            clock += 61_000L
            val fresh = VaultPreviewPolicy.Entry("decrypted_$index.tmp", clock)
            val toDelete = VaultPreviewPolicy.toDelete(entries, clock, keepName = fresh.name)
            val deletedNames = toDelete.map { it.name }.toSet()
            entries = entries.filterNot { it.name in deletedNames } + fresh
        }
        assertTrue(
            "в кэше осталось ${entries.size} копий: ${entries.map { it.name }}",
            entries.size <= VaultPreviewPolicy.MAX_PREVIEW_FILES
        )
    }
}
