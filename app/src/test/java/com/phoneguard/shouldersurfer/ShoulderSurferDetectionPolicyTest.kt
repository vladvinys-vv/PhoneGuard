package com.phoneguard.shouldersurfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила «кто-то смотрит в экран».
 *
 * Ошибка здесь не роняет приложение: она либо превращает защиту в генератор ложных тревог
 * (телефон пищит каждые полминуты), либо делает её мёртвой (лицо есть, реакции нет). Поэтому
 * пороги наклона, поворота, открытости глаз и частоты предупреждений закреплены тестами.
 */
class ShoulderSurferDetectionPolicyTest {

    // --- положение головы ------------------------------------------------------

    @Test
    fun faceStraightToScreenIsDetected() {
        assertTrue(ShoulderSurferDetectionPolicy.isFacingScreen(pitchDegrees = 0f, yawDegrees = 0f))
    }

    @Test
    fun boundariesAreInclusive() {
        assertTrue(ShoulderSurferDetectionPolicy.isFacingScreen(20f, 0f))
        assertTrue(ShoulderSurferDetectionPolicy.isFacingScreen(-20f, 0f))
        assertTrue(ShoulderSurferDetectionPolicy.isFacingScreen(0f, 30f))
        assertTrue(ShoulderSurferDetectionPolicy.isFacingScreen(0f, -30f))
    }

    @Test
    fun headTiltedTooFarIsNotFacingScreen() {
        assertFalse(ShoulderSurferDetectionPolicy.isFacingScreen(20.1f, 0f))
        assertFalse(ShoulderSurferDetectionPolicy.isFacingScreen(-25f, 0f))
    }

    @Test
    fun profileViewIsNotFacingScreen() {
        // В профиль человек на экран не смотрит — это не подглядывание.
        assertFalse(ShoulderSurferDetectionPolicy.isFacingScreen(0f, 30.1f))
        assertFalse(ShoulderSurferDetectionPolicy.isFacingScreen(0f, -60f))
    }

    // --- глаза ----------------------------------------------------------------

    @Test
    fun bothEyesOpenIsSuspicious() {
        assertTrue(ShoulderSurferDetectionPolicy.areEyesOpen(0.9f, 0.8f))
    }

    @Test
    fun oneClosedEyeIsNotSuspicious() {
        // Моргание не должно считаться подглядыванием.
        assertFalse(ShoulderSurferDetectionPolicy.areEyesOpen(0.9f, 0.1f))
        assertFalse(ShoulderSurferDetectionPolicy.areEyesOpen(0.05f, 0.9f))
    }

    @Test
    fun probabilityBoundaryIsExclusive() {
        // Порог строгий: ровно 0.3 — ещё «не открыты» (так было в сервисе до выноса правил).
        assertFalse(ShoulderSurferDetectionPolicy.areEyesOpen(0.3f, 0.3f))
        assertTrue(ShoulderSurferDetectionPolicy.areEyesOpen(0.31f, 0.31f))
    }

    @Test
    fun missingProbabilityCountsAsOpen() {
        // ML Kit не отдал вероятность (лицо частично перекрыто). Текущее поведение: считаем
        // глаза открытыми — это зафиксировано, а не «как получится».
        assertTrue(ShoulderSurferDetectionPolicy.areEyesOpen(null, null))
        assertTrue(ShoulderSurferDetectionPolicy.areEyesOpen(null, 0.9f))
    }

    @Test
    fun missingProbabilityDoesNotOverrideClosedEye() {
        assertFalse(ShoulderSurferDetectionPolicy.areEyesOpen(null, 0.1f))
        assertFalse(ShoulderSurferDetectionPolicy.areEyesOpen(0.1f, null))
    }

    // --- полное правило --------------------------------------------------------

    @Test
    fun suspiciousFaceRequiresBothPositionAndOpenEyes() {
        assertTrue(ShoulderSurferDetectionPolicy.isSuspiciousFace(0f, 0f, 0.9f, 0.9f))
        assertFalse(ShoulderSurferDetectionPolicy.isSuspiciousFace(40f, 0f, 0.9f, 0.9f))
        assertFalse(ShoulderSurferDetectionPolicy.isSuspiciousFace(0f, 70f, 0.9f, 0.9f))
        assertFalse(ShoulderSurferDetectionPolicy.isSuspiciousFace(0f, 0f, 0.1f, 0.9f))
    }

    // --- частота предупреждений ----------------------------------------------

    @Test
    fun firstDetectionAlwaysAlerts() {
        assertTrue(ShoulderSurferDetectionPolicy.shouldAlert(nowElapsedMs = 5_000L, lastAlertElapsedMs = 0L))
    }

    @Test
    fun repeatedDetectionsWithinIntervalAreSuppressed() {
        val last = 100_000L
        assertFalse(ShoulderSurferDetectionPolicy.shouldAlert(last + 1L, last))
        assertFalse(
            ShoulderSurferDetectionPolicy.shouldAlert(
                last + ShoulderSurferDetectionPolicy.MIN_ALERT_INTERVAL_MS - 1L,
                last
            )
        )
    }

    @Test
    fun alertIsAllowedExactlyAtInterval() {
        val last = 100_000L
        assertTrue(
            ShoulderSurferDetectionPolicy.shouldAlert(
                last + ShoulderSurferDetectionPolicy.MIN_ALERT_INTERVAL_MS,
                last
            )
        )
    }

    @Test
    fun clockGoingBackwardsDoesNotSilenceTheAlert() {
        // Монотонные часы не должны идти назад, но если это случилось — лучше предупредить.
        assertTrue(ShoulderSurferDetectionPolicy.shouldAlert(nowElapsedMs = 90_000L, lastAlertElapsedMs = 100_000L))
    }

    @Test
    fun customIntervalIsRespected() {
        assertFalse(ShoulderSurferDetectionPolicy.shouldAlert(1_000L, 500L, intervalMs = 10_000L))
        assertTrue(ShoulderSurferDetectionPolicy.shouldAlert(11_000L, 500L, intervalMs = 10_000L))
    }
}
