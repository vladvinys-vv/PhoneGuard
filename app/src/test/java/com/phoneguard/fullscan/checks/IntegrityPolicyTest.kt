package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила «система скомпрометирована».
 *
 * Раньше это было `compromisedIndicators >= 2` внутри чека: все признаки весили
 * одинаково, поэтому разблокированный загрузчик сам по себе не считался проблемой,
 * а старая прошивка в паре с любым другим «слабым» признаком давала ложное
 * «скомпрометировано».
 */
class IntegrityPolicyTest {

    private val yearMs = 365L * 24 * 60 * 60 * 1000

    @Test
    fun `заводское состояние — не скомпрометировано`() {
        assertFalse(IntegrityPolicy.isCompromised(emptySet()))
    }

    @Test
    fun `orange verified boot — достаточно одного признака`() {
        assertTrue(IntegrityPolicy.isVerifiedBootUnsafe("orange"))
        assertTrue(
            IntegrityPolicy.isCompromised(setOf(IntegrityPolicy.Signal.VERIFIED_BOOT_UNSAFE))
        )
    }

    @Test
    fun `yellow verified boot тоже считается`() {
        assertTrue(IntegrityPolicy.isVerifiedBootUnsafe("yellow"))
    }

    @Test
    fun `green и неизвестное состояние безопасны`() {
        assertFalse(IntegrityPolicy.isVerifiedBootUnsafe("green"))
        assertFalse(IntegrityPolicy.isVerifiedBootUnsafe("unknown"))
        assertFalse(IntegrityPolicy.isVerifiedBootUnsafe(null))
    }

    @Test
    fun `загрузчик разблокирован, только если flash_locked не 1`() {
        assertFalse(IntegrityPolicy.isBootloaderUnlocked("1"))
        assertFalse(IntegrityPolicy.isBootloaderUnlocked(null))
        assertTrue(IntegrityPolicy.isBootloaderUnlocked("0"))
        assertTrue(IntegrityPolicy.isBootloaderUnlocked(""))
        assertTrue(
            IntegrityPolicy.isCompromised(setOf(IntegrityPolicy.Signal.BOOTLOADER_UNLOCKED))
        )
    }

    @Test
    fun `su в системном разделе — сильный признак`() {
        assertTrue(
            IntegrityPolicy.isCompromised(setOf(IntegrityPolicy.Signal.SYSTEM_ROOT_FILES))
        )
    }

    @Test
    fun `сборка на test-keys — сильный признак`() {
        assertTrue(IntegrityPolicy.hasDebugBuildMarks("test-keys", "google/sdk/gphone:9/PPR1/123:user/release-keys"))
        assertTrue(IntegrityPolicy.hasDebugBuildMarks("dev-keys", ""))
        assertTrue(IntegrityPolicy.hasDebugBuildMarks("release-keys", "vendor/phone/phone:11/RQ2/userdebug/123"))
        assertTrue(IntegrityPolicy.hasDebugBuildMarks("release-keys", "vendor/phone/phone:11/eng.build/123"))
        // Голый тег `eng` — инженерный образ. Раньше его распознавала только root-проверка
        // (искала подстроку «eng»), поэтому отчёты об одном устройстве расходились.
        assertTrue(IntegrityPolicy.hasDebugBuildMarks("eng", ""))
        assertTrue(IntegrityPolicy.hasDebugBuildMarks("release-keys, eng", ""))
        // Постороннее слово с «eng» внутри — не отладочная сборка.
        assertFalse(IntegrityPolicy.hasDebugBuildMarks("avengers-keys", ""))
        assertFalse(IntegrityPolicy.hasDebugBuildMarks("release-keys", "google/redfin/redfin:11/RQ3/release-keys"))
        assertTrue(
            IntegrityPolicy.isCompromised(setOf(IntegrityPolicy.Signal.DEBUG_BUILD))
        )
    }

    @Test
    fun `старая прошивка сама по себе — не компрометация`() {
        // Главное отличие от прежнего поведения: один слабый признак ничего не решает.
        assertFalse(
            IntegrityPolicy.isCompromised(setOf(IntegrityPolicy.Signal.STALE_FIRMWARE))
        )
    }

    @Test
    fun `старая прошивка вместе с отладочной сборкой — компрометация`() {
        assertTrue(
            IntegrityPolicy.isCompromised(
                setOf(
                    IntegrityPolicy.Signal.STALE_FIRMWARE,
                    IntegrityPolicy.Signal.DEBUG_BUILD
                )
            )
        )
    }

    @Test
    fun `порог устаревания прошивки — два года`() {
        val now = 1_700_000_000_000L
        assertFalse(IntegrityPolicy.isStaleBuild(now - yearMs, now))
        assertTrue(IntegrityPolicy.isStaleBuild(now - 2 * yearMs, now))
        // Дата в будущем (перевод часов назад) не должна давать «устаревшую» прошивку.
        assertFalse(IntegrityPolicy.isStaleBuild(now + yearMs, now))
        assertFalse(IntegrityPolicy.isStaleBuild(0L, now))
        assertEquals(0, IntegrityPolicy.buildAgeYears(now + yearMs, now))
        assertEquals(3, IntegrityPolicy.buildAgeYears(now - 3 * yearMs - 1000, now))
    }

    @Test
    fun `кастомная прошивка — отдельная информационная строка`() {
        assertTrue(IntegrityPolicy.isCustomRom("LineageOS/lineage_device/…"))
        assertTrue(IntegrityPolicy.isCustomRom("custom/rom/…"))
        assertFalse(IntegrityPolicy.isCustomRom("google/redfin/redfin:11/RQ3/release-keys"))
    }
}
