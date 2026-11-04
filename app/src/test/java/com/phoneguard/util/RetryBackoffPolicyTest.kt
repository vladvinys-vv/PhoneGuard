package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Повтор операции после неудачи.
 *
 * Две ошибки здесь стоят дорого и обе тихие: повтор «постоянной» ошибки (нет разрешения) —
 * это мигающий переключатель в интерфейсе без всякой пользы, а отсутствие повтора у
 * «временной» (камера занята другим приложением) — выключенная защита из-за случайной помехи.
 */
class RetryBackoffPolicyTest {

    private val transient = RetryBackoffPolicy.FailureKind.TRANSIENT
    private val permanent = RetryBackoffPolicy.FailureKind.PERMANENT

    @Test
    fun permanentFailureNeverRetries() {
        for (failures in 0..5) {
            val decision = RetryBackoffPolicy.decide(failures, permanent)
            assertFalse("failures=$failures", decision.shouldRetry)
            assertEquals(0L, decision.delayMs)
        }
    }

    @Test
    fun firstTransientFailureRetriesAfterInitialDelay() {
        val decision = RetryBackoffPolicy.decide(failuresSoFar = 0, kind = transient)
        assertTrue(decision.shouldRetry)
        assertEquals(RetryBackoffPolicy.INITIAL_DELAY_MS, decision.delayMs)
    }

    @Test
    fun attemptsAreCappedAtMaxAttempts() {
        // MAX_ATTEMPTS = 3: после первой и второй неудачи повторяем, после третьей — нет.
        assertTrue(RetryBackoffPolicy.decide(0, transient).shouldRetry)
        assertTrue(RetryBackoffPolicy.decide(1, transient).shouldRetry)
        assertFalse(RetryBackoffPolicy.decide(RetryBackoffPolicy.MAX_ATTEMPTS - 1, transient).shouldRetry)
        assertFalse(RetryBackoffPolicy.decide(RetryBackoffPolicy.MAX_ATTEMPTS, transient).shouldRetry)
    }

    @Test
    fun delayGrowsExponentially() {
        assertEquals(1_000L, RetryBackoffPolicy.delayFor(1))
        assertEquals(2_000L, RetryBackoffPolicy.delayFor(2))
        assertEquals(4_000L, RetryBackoffPolicy.delayFor(3))
        assertEquals(8_000L, RetryBackoffPolicy.delayFor(4))
    }

    @Test
    fun delayIsCapped() {
        assertEquals(RetryBackoffPolicy.MAX_DELAY_MS, RetryBackoffPolicy.delayFor(5))
        assertEquals(RetryBackoffPolicy.MAX_DELAY_MS, RetryBackoffPolicy.delayFor(10))
        assertEquals(RetryBackoffPolicy.MAX_DELAY_MS, RetryBackoffPolicy.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun zeroOrNegativeAttemptMeansInitialDelay() {
        assertEquals(RetryBackoffPolicy.INITIAL_DELAY_MS, RetryBackoffPolicy.delayFor(0))
        assertEquals(RetryBackoffPolicy.INITIAL_DELAY_MS, RetryBackoffPolicy.delayFor(-5))
    }

    @Test
    fun negativeFailureCountIsTreatedAsBrokenState() {
        // Отрицательное число неудач — ошибка вызывающего; повторять «на всякий случай» нельзя,
        // иначе цикл повторов может стать бесконечным.
        assertFalse(RetryBackoffPolicy.decide(failuresSoFar = -1, kind = transient).shouldRetry)
    }

    @Test
    fun stopDecisionHasNoDelay() {
        val decision = RetryBackoffPolicy.decide(failuresSoFar = 10, kind = transient)
        assertEquals(RetryBackoffPolicy.Decision.STOP, decision)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun attemptSequenceMatchesMaxAttempts() {
        // Имитация реального цикла: считаем, сколько всего попыток получится при временных ошибках.
        var failures = 0
        var attempts = 1
        while (RetryBackoffPolicy.decide(failures, transient).shouldRetry) {
            failures++
            attempts++
        }
        assertEquals(RetryBackoffPolicy.MAX_ATTEMPTS, attempts)
    }

    @Test
    fun delaysInCycleAreNonDecreasing() {
        var previous = 0L
        for (attempt in 1..6) {
            val delay = RetryBackoffPolicy.delayFor(attempt)
            assertTrue("задержка уменьшилась на попытке $attempt: $delay < $previous", delay >= previous)
            previous = delay
        }
    }
}
