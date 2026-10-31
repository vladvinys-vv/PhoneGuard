package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ограничитель попыток PIN: подбор по SMS не должен быть бесконечным.
 */
class PinAttemptLimiterTest {

    private val now = 1_700_000_000_000L

    @Test
    fun successResetsState() {
        val state = PinAttemptLimiter.afterSuccess()
        assertEquals(0, state.attempts)
        assertEquals(0L, state.lockedUntil)
        assertFalse(state.isLocked(now))
    }

    @Test
    fun attemptsAccumulateUntilLockout() {
        var state = PinAttemptLimiter.State()
        repeat(PinAttemptLimiter.MAX_ATTEMPTS - 1) { index ->
            state = PinAttemptLimiter.afterFailure(state, now)
            assertEquals(index + 1, state.attempts)
            assertFalse("блокировка не должна наступать раньше", state.isLocked(now))
        }
        state = PinAttemptLimiter.afterFailure(state, now)
        assertTrue("после MAX_ATTEMPTS неудач PIN должен блокироваться", state.isLocked(now))
        assertEquals(now + PinAttemptLimiter.LOCKOUT_MS, state.lockedUntil)
    }

    @Test
    fun lockoutExpiresAfterFifteenMinutes() {
        var state = PinAttemptLimiter.State()
        repeat(PinAttemptLimiter.MAX_ATTEMPTS) { state = PinAttemptLimiter.afterFailure(state, now) }
        val locked = state
        assertTrue(locked.isLocked(now + PinAttemptLimiter.LOCKOUT_MS - 1))
        assertFalse(locked.isLocked(now + PinAttemptLimiter.LOCKOUT_MS))
        assertEquals(0L, locked.remainingMs(now + PinAttemptLimiter.LOCKOUT_MS + 1))
    }

    @Test
    fun failuresDuringLockoutDoNotExtendIt() {
        var state = PinAttemptLimiter.State()
        repeat(PinAttemptLimiter.MAX_ATTEMPTS) { state = PinAttemptLimiter.afterFailure(state, now) }
        val lockedUntil = state.lockedUntil
        val whileLocked = PinAttemptLimiter.afterFailure(state, now + 1_000L)
        assertEquals(lockedUntil, whileLocked.lockedUntil)
        assertEquals(state.attempts, whileLocked.attempts)
    }
}
