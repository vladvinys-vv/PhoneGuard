package com.phoneguard.threatintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Когда обновлять публичный фид фишинговых URL.
 *
 * Две ошибки, которые здесь зафиксированы: параллельные проверки хостов тянули фид каждая
 * заново (до 8 МБ трафика), а решение «фид свежий» считалось по стеновым часам, поэтому
 * перевод времени мог держать фид «свежим» бесконечно.
 */
class ThreatFeedRefreshPolicyTest {

    @Test
    fun firstCheckAttemptsLoad() {
        // Пока попыток не было, «когда можно» равно нулю — грузим сразу.
        assertEquals(
            ThreatFeedRefreshPolicy.Decision.ATTEMPT,
            ThreatFeedRefreshPolicy.decide(nowElapsedMs = 0L, nextAttemptElapsedMs = 0L)
        )
        assertEquals(
            ThreatFeedRefreshPolicy.Decision.ATTEMPT,
            ThreatFeedRefreshPolicy.decide(nowElapsedMs = 5_000L, nextAttemptElapsedMs = 0L)
        )
    }

    @Test
    fun beforeDeadlineUsesCachedFeed() {
        assertEquals(
            ThreatFeedRefreshPolicy.Decision.WAIT,
            ThreatFeedRefreshPolicy.decide(nowElapsedMs = 1_000L, nextAttemptElapsedMs = 2_000L)
        )
    }

    @Test
    fun deadlineIsInclusive() {
        // Ровно в назначенный момент грузим, а не ждём ещё один тик.
        assertEquals(
            ThreatFeedRefreshPolicy.Decision.ATTEMPT,
            ThreatFeedRefreshPolicy.decide(nowElapsedMs = 2_000L, nextAttemptElapsedMs = 2_000L)
        )
    }

    @Test
    fun successSchedulesNextAttemptAfterTtl() {
        assertEquals(
            1_000L + ThreatFeedRefreshPolicy.FEED_TTL_MS,
            ThreatFeedRefreshPolicy.nextAttemptAfterSuccess(1_000L)
        )
    }

    @Test
    fun emptyFeedRetriesSoonerThanTtl() {
        val next = ThreatFeedRefreshPolicy.nextAttemptAfterEmpty(1_000L)
        assertTrue("пустой ответ не должен ждать полный TTL", next < 1_000L + ThreatFeedRefreshPolicy.FEED_TTL_MS)
        assertEquals(1_000L + ThreatFeedRefreshPolicy.EMPTY_FEED_RETRY_MS, next)
    }

    @Test
    fun failedFetchRetriesSoonerThanEmptyFeed() {
        // Ошибка сети — повод повторить раньше, чем пустой ответ: фид не обновлялся вовсе.
        assertTrue(
            ThreatFeedRefreshPolicy.FAILED_FETCH_RETRY_MS < ThreatFeedRefreshPolicy.EMPTY_FEED_RETRY_MS
        )
        assertEquals(
            1_000L + ThreatFeedRefreshPolicy.FAILED_FETCH_RETRY_MS,
            ThreatFeedRefreshPolicy.nextAttemptAfterFailure(1_000L)
        )
    }

    @Test
    fun customIntervalsAreHonoured() {
        assertEquals(10L, ThreatFeedRefreshPolicy.nextAttemptAfterSuccess(5L, ttlMs = 5L))
        assertEquals(15L, ThreatFeedRefreshPolicy.nextAttemptAfterEmpty(5L, retryMs = 10L))
        assertEquals(25L, ThreatFeedRefreshPolicy.nextAttemptAfterFailure(5L, retryMs = 20L))
    }

    @Test
    fun longUptimeDoesNotOverflow() {
        val farFuture = Long.MAX_VALUE / 4
        assertTrue(
            ThreatFeedRefreshPolicy.nextAttemptAfterSuccess(farFuture) > farFuture
        )
    }

    @Test
    fun decisionDependsOnIntervalsNotOnWallClockDate() {
        // Монотонные часы: значения могут быть любыми, важно только соотношение.
        assertEquals(
            ThreatFeedRefreshPolicy.Decision.WAIT,
            ThreatFeedRefreshPolicy.decide(nowElapsedMs = 10L, nextAttemptElapsedMs = 11L)
        )
        assertEquals(
            ThreatFeedRefreshPolicy.Decision.ATTEMPT,
            ThreatFeedRefreshPolicy.decide(nowElapsedMs = 11L, nextAttemptElapsedMs = 11L)
        )
    }
}
