package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ограничитель попыток PIN: подбор по SMS не должен быть бесконечным,
 * в том числе при переводе системных часов.
 */
class PinAttemptLimiterTest {

    private val now = 1_700_000_000_000L

    /** Монотонные часы «после загрузки»: 100 часов аптайма. */
    private val up = 360_000_000L

    private fun lockedState(wall: Long = now, elapsed: Long = up): PinAttemptLimiter.State {
        var state = PinAttemptLimiter.State()
        repeat(PinAttemptLimiter.MAX_ATTEMPTS) { state = PinAttemptLimiter.afterFailure(state, wall, elapsed) }
        return state
    }

    @Test
    fun successResetsState() {
        val state = PinAttemptLimiter.afterSuccess()
        assertEquals(0, state.attempts)
        assertEquals(0L, state.lockedUntil)
        assertEquals(0L, state.lockoutRemainingMs)
        assertEquals(0L, state.lockoutElapsedMark)
        assertFalse(state.isLocked(now, up))
    }

    @Test
    fun attemptsAccumulateUntilLockout() {
        var state = PinAttemptLimiter.State()
        repeat(PinAttemptLimiter.MAX_ATTEMPTS - 1) { index ->
            state = PinAttemptLimiter.afterFailure(state, now, up)
            assertEquals(index + 1, state.attempts)
            assertFalse("блокировка не должна наступать раньше", state.isLocked(now, up))
        }
        state = PinAttemptLimiter.afterFailure(state, now, up)
        assertTrue("после MAX_ATTEMPTS неудач PIN должен блокироваться", state.isLocked(now, up))
        assertEquals(now + PinAttemptLimiter.LOCKOUT_MS, state.lockedUntil)
        assertEquals(PinAttemptLimiter.LOCKOUT_MS, state.lockoutRemainingMs)
    }

    @Test
    fun lockoutExpiresAfterFifteenMinutes() {
        val locked = lockedState()
        assertTrue(locked.isLocked(now + PinAttemptLimiter.LOCKOUT_MS - 1, up + PinAttemptLimiter.LOCKOUT_MS - 1))
        assertFalse(locked.isLocked(now + PinAttemptLimiter.LOCKOUT_MS, up + PinAttemptLimiter.LOCKOUT_MS))
        assertEquals(
            0L,
            locked.remainingMs(now + PinAttemptLimiter.LOCKOUT_MS + 1, up + PinAttemptLimiter.LOCKOUT_MS + 1)
        )
    }

    @Test
    fun failuresDuringLockoutDoNotExtendIt() {
        val locked = lockedState()
        val whileLocked = PinAttemptLimiter.afterFailure(locked, now + 1_000L, up + 1_000L)
        assertEquals(locked.lockedUntil, whileLocked.lockedUntil)
        assertEquals(locked.lockoutElapsedMark, whileLocked.lockoutElapsedMark)
        assertEquals(locked.attempts, whileLocked.attempts)
    }

    @Test
    fun failureAfterLockoutStartsCountingAgain() {
        val locked = lockedState()
        val wallAfter = now + PinAttemptLimiter.LOCKOUT_MS
        val elapsedAfter = up + PinAttemptLimiter.LOCKOUT_MS
        val afterExpiry = PinAttemptLimiter.afterFailure(locked, wallAfter, elapsedAfter)
        assertEquals(1, afterExpiry.attempts)
        assertFalse(afterExpiry.isLocked(wallAfter, elapsedAfter))
    }

    @Test
    fun remainingTimeIsZeroWhenNotLocked() {
        assertEquals(0L, PinAttemptLimiter.State(attempts = 3).remainingMs(now, up))
        assertEquals(0L, PinAttemptLimiter.State().remainingMs(now, up))
    }

    @Test
    fun remainingTimeCountsDownDuringLockout() {
        val locked = lockedState()
        assertEquals(PinAttemptLimiter.LOCKOUT_MS, locked.remainingMs(now, up))
        assertEquals(
            PinAttemptLimiter.LOCKOUT_MS / 2,
            locked.remainingMs(now + PinAttemptLimiter.LOCKOUT_MS / 2, up + PinAttemptLimiter.LOCKOUT_MS / 2)
        )
    }

    @Test
    fun successDuringLockoutClearsIt() {
        val unlocked = PinAttemptLimiter.afterSuccess()
        assertFalse(unlocked.isLocked(now, up))
        assertEquals(0, unlocked.attempts)
    }

    @Test
    fun lockoutPolicyStaysFiveAttemptsAndFifteenMinutes() {
        // Значение меняется только вместе с текстом в интерфейсе и документацией.
        assertEquals(5, PinAttemptLimiter.MAX_ATTEMPTS)
        assertEquals(15L * 60L * 1000L, PinAttemptLimiter.LOCKOUT_MS)
    }

    @Test
    fun attemptsAreCountedPerStateAndDoNotLeakBetweenStates() {
        val first = PinAttemptLimiter.afterFailure(PinAttemptLimiter.State(), now, up)
        val second = PinAttemptLimiter.afterFailure(PinAttemptLimiter.State(), now, up)
        assertEquals(1, first.attempts)
        assertEquals(1, second.attempts)
    }

    // --- Перевод системных часов не должен снимать блокировку ---

    @Test
    fun movingWallClockForwardDoesNotEndLockout() {
        val locked = lockedState()
        val twoHoursLater = now + 2 * 60 * 60 * 1000L
        // Часы перевели вперёд, но по монотонным часам прошла одна секунда.
        assertTrue(locked.isLocked(twoHoursLater, up + 1_000L))
        assertEquals(
            PinAttemptLimiter.LOCKOUT_MS - 1_000L,
            locked.remainingMs(twoHoursLater, up + 1_000L)
        )
    }

    @Test
    fun movingWallClockBackwardDoesNotExtendLockout() {
        val locked = lockedState()
        // Часы откатили на неделю назад: остаток всё равно считается по аптайму.
        val weekEarlier = now - 7 * 24 * 60 * 60 * 1000L
        assertEquals(
            PinAttemptLimiter.LOCKOUT_MS - 60_000L,
            locked.remainingMs(weekEarlier, up + 60_000L)
        )
    }

    @Test
    fun lockoutSurvivesReboot() {
        val locked = lockedState()
        // После перезагрузки elapsedRealtime() начинается с нуля — опираемся на стену.
        assertTrue(locked.isLocked(now + 60_000L, 60_000L))
        assertFalse(locked.isLocked(now + PinAttemptLimiter.LOCKOUT_MS, 1_000L))
    }

    @Test
    fun rebootWithClockMovedBackwardDoesNotLockForever() {
        val locked = lockedState()
        val yearEarlier = now - 365L * 24 * 60 * 60 * 1000L
        // Остаток обрезан длительностью блокировки, а не «до скончания века».
        assertEquals(PinAttemptLimiter.LOCKOUT_MS, locked.remainingMs(yearEarlier, 5_000L))
        assertTrue(locked.isLocked(yearEarlier, 5_000L))
    }

    @Test
    fun stateWithoutMonotonicMarksFallsBackToWallClock() {
        // Так выглядит состояние, записанное предыдущей версией приложения.
        val legacy = PinAttemptLimiter.State(attempts = 0, lockedUntil = now + 60_000L)
        assertTrue(legacy.isLocked(now, up))
        assertEquals(60_000L, legacy.remainingMs(now, up))
        assertFalse(legacy.isLocked(now + 60_000L, up))
    }
}
