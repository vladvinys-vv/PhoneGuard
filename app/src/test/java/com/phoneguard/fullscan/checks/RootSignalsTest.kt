package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вердикт root-детекта: сколько индикаторов достаточно и какая при этом «уверенность».
 *
 * До выноса в [RootSignals] порог `>= 3` и деление `indicators / totalChecks` жили внутри метода,
 * читающего `Build.TAGS`, `PackageManager` и файловую систему, — то есть вердикт сканирования
 * не проверялся вообще. Ошибка в пороге здесь означает либо «рутованное устройство чистое»,
 * либо «все устройства рутованные».
 */
class RootSignalsTest {

    @Test
    fun twoIndicatorsAreNotEnough() {
        // Порог намеренно равен трём: одна-две эвристики дают ложные срабатывания
        // (например, `test-keys` на кастомной прошивке без root-прав).
        val verdict = RootSignals.verdict(indicatorCount = 2, checkCount = 6)
        assertFalse(verdict.isRooted)
        assertEquals(2f / 6f, verdict.confidence, 0.0001f)
    }

    @Test
    fun threeIndicatorsMeanRooted() {
        assertTrue(RootSignals.verdict(indicatorCount = 3, checkCount = 6).isRooted)
    }

    @Test
    fun allIndicatorsMeanFullConfidence() {
        val verdict = RootSignals.verdict(indicatorCount = 6, checkCount = 6)
        assertTrue(verdict.isRooted)
        assertEquals(1.0f, verdict.confidence, 0.0001f)
    }

    @Test
    fun noIndicatorsMeanZeroConfidence() {
        val verdict = RootSignals.verdict(indicatorCount = 0, checkCount = 6)
        assertFalse(verdict.isRooted)
        assertEquals(0.0f, verdict.confidence, 0.0001f)
    }

    @Test
    fun confidenceIsNotNaNWhenNothingWasChecked() {
        // Деление на ноль дало бы NaN, а NaN в UI после форматирования выглядит
        // как «уверенность 100 %» — самый неудобный из возможных багов.
        val verdict = RootSignals.verdict(indicatorCount = 0, checkCount = 0)
        assertFalse(verdict.isRooted)
        assertEquals(0.0f, verdict.confidence, 0.0001f)
        assertFalse("confidence не должен быть NaN", verdict.confidence.isNaN())
    }

    @Test
    fun indicatorsCannotExceedTheCheckCount() {
        // Защита от рассинхронизации счётчиков: 5 индикаторов при 3 проверках
        // не должны давать «уверенность» больше 100 %.
        val verdict = RootSignals.verdict(indicatorCount = 5, checkCount = 3)
        assertTrue(verdict.isRooted)
        assertEquals(1.0f, verdict.confidence, 0.0001f)
    }

    @Test
    fun negativeIndicatorsAreClamped() {
        val verdict = RootSignals.verdict(indicatorCount = -1, checkCount = 6)
        assertEquals(0.0f, verdict.confidence, 0.0001f)
        assertFalse(verdict.isRooted)
    }

    @Test
    fun thresholdIsExactlyThree() {
        assertEquals(3, RootSignals.ROOTED_THRESHOLD)
    }

    @Test
    fun buildTagsAreMatchedCaseInsensitively() {
        assertTrue(RootSignals.hasTestKeys("test-keys"))
        assertTrue(RootSignals.hasTestKeys("release-keys,TEST-KEYS"))
        assertTrue(RootSignals.hasTestKeys("dev-keys"))
        assertTrue("инженерный образ тоже признак", RootSignals.hasTestKeys("eng"))
    }

    @Test
    fun normalBuildTagsAreNotSuspicious() {
        assertFalse(RootSignals.hasTestKeys("release-keys"))
        assertFalse(RootSignals.hasTestKeys(""))
        assertFalse(RootSignals.hasTestKeys(null))
    }

    @Test
    fun engineeringTagIsNotMatchedInsideAnotherWord() {
        // Раньше здесь искалась подстрока «eng», и тег «avengers-keys» объявлялся
        // отладочной сборкой. Теперь сравнение идёт по целым тегам: постороннее слово
        // находкой не считается, а голый тег «eng» (инженерный образ) — считается.
        assertFalse(RootSignals.hasTestKeys("avengers-keys"))
        assertTrue(RootSignals.hasTestKeys("release-keys,eng"))
        assertTrue(RootSignals.hasTestKeys("eng,test-keys"))
    }

    @Test
    fun debugBuildRuleAgreesWithIntegrityPolicy() {
        // Два отчёта об одном устройстве не должны расходиться: раньше root-проверка
        // считала образ с тегом «eng» отладочным, а проверка целостности — нет.
        for (tags in listOf("eng", "test-keys", "dev-keys", "release-keys", "release-keys, eng", "avengers-keys")) {
            assertEquals(
                "теги: $tags",
                IntegrityPolicy.hasTestKeys(tags),
                RootSignals.hasTestKeys(tags)
            )
        }
    }

    @Test
    fun signalListsAreNotEmptyAndHaveNoDuplicates() {
        assertEquals(RootSignals.SU_PATHS.size, RootSignals.SU_PATHS.distinct().size)
        assertEquals(RootSignals.ROOT_PACKAGE_PREFIXES.size, RootSignals.ROOT_PACKAGE_PREFIXES.distinct().size)
        assertEquals(RootSignals.SYSTEM_MARKERS.size, RootSignals.SYSTEM_MARKERS.distinct().size)
        assertTrue(RootSignals.SU_PATHS.all { it.startsWith("/") })
        assertTrue(RootSignals.SYSTEM_MARKERS.all { it.startsWith("/") })
        assertTrue(RootSignals.SU_PATHS.any { it.endsWith("/su") })
    }
}
