package com.phoneguard.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Уровень риска spyware и общий балл устройства.
 *
 * От этих правил зависит, увидит пользователь «критическую угрозу» или приложение спрячется в
 * общем списке. Правила жили в репозитории с DAO и `PackageManager`, поэтому не исполнялись
 * тестами; теперь проверяются напрямую.
 */
class SpywareRiskScoringTest {

    @Test
    fun `no indicators means low risk`() {
        assertEquals(RiskLevel.LOW, SpywareRiskScoring.level(emptyList()))
    }

    @Test
    fun `single indicator is medium`() {
        assertEquals(RiskLevel.MEDIUM, SpywareRiskScoring.level(listOf(SpywareIndicator.HIDDEN_LAUNCHER)))
        assertEquals(RiskLevel.MEDIUM, SpywareRiskScoring.level(listOf(SpywareIndicator.EXPIRED_CERTIFICATE)))
        assertEquals(RiskLevel.MEDIUM, SpywareRiskScoring.level(listOf(SpywareIndicator.SIDELOADED)))
    }

    @Test
    fun `device admin with accessibility service is critical`() {
        assertEquals(
            RiskLevel.CRITICAL,
            SpywareRiskScoring.level(
                listOf(SpywareIndicator.DEVICE_ADMIN, SpywareIndicator.ACCESSIBILITY_SERVICE)
            )
        )
    }

    @Test
    fun `device admin or accessibility service alone is high`() {
        assertEquals(RiskLevel.HIGH, SpywareRiskScoring.level(listOf(SpywareIndicator.DEVICE_ADMIN)))
        assertEquals(RiskLevel.HIGH, SpywareRiskScoring.level(listOf(SpywareIndicator.ACCESSIBILITY_SERVICE)))
    }

    @Test
    fun `modified apk after install is high`() {
        assertEquals(RiskLevel.HIGH, SpywareRiskScoring.level(listOf(SpywareIndicator.MODIFIED_AFTER_INSTALL)))
    }

    @Test
    fun `debug signature on sideloaded app is high`() {
        assertEquals(
            RiskLevel.HIGH,
            SpywareRiskScoring.level(listOf(SpywareIndicator.DEBUG_SIGNATURE, SpywareIndicator.SIDELOADED))
        )
    }

    @Test
    fun `any two indicators are high`() {
        assertEquals(
            RiskLevel.HIGH,
            SpywareRiskScoring.level(listOf(SpywareIndicator.WEAK_SIGNATURE, SpywareIndicator.HIDDEN_LAUNCHER))
        )
    }

    @Test
    fun `duplicated indicator does not inflate the level`() {
        // Признаки сравниваются как множество: иначе повтор одного индикатора давал бы два
        // «разных» признака и уровень HIGH вместо MEDIUM.
        assertEquals(
            RiskLevel.MEDIUM,
            SpywareRiskScoring.level(listOf(SpywareIndicator.SIDELOADED, SpywareIndicator.SIDELOADED))
        )
    }

    @Test
    fun `weights match the scale`() {
        assertEquals(10, SpywareRiskScoring.weight(RiskLevel.LOW))
        assertEquals(25, SpywareRiskScoring.weight(RiskLevel.MEDIUM))
        assertEquals(50, SpywareRiskScoring.weight(RiskLevel.HIGH))
        assertEquals(100, SpywareRiskScoring.weight(RiskLevel.CRITICAL))
    }

    @Test
    fun `total score is clamped to the screen scale`() {
        assertEquals(0, SpywareRiskScoring.totalScore(emptyList()))
        assertEquals(35, SpywareRiskScoring.totalScore(listOf(RiskLevel.LOW, RiskLevel.MEDIUM)))
        assertEquals(100, SpywareRiskScoring.totalScore(listOf(RiskLevel.CRITICAL)))
        assertEquals(
            "сумма выше 100 обрезается",
            100,
            SpywareRiskScoring.totalScore(List(10) { RiskLevel.HIGH })
        )
    }
}
