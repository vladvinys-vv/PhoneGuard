package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кэш по времени жизни.
 *
 * Часы подставляются, поэтому «сутки прошли» проверяются мгновенно. Отдельно проверяется
 * граница срока: ровно `validityMs` — это уже устаревание, иначе «24 часа» растягивались бы
 * на неопределённый срок, а испорченные часы устройства — на неопределённый тоже.
 */
class TtlCacheTest {

    private var now = 1_000L
    private val cache = TtlCache<String>(validityMs = 100L, clock = { now })

    @Test
    fun emptyCacheReturnsNull() {
        assertNull(cache.getOrNull())
        assertFalse(cache.hasEntry())
    }

    @Test
    fun valueIsReturnedWhileFresh() {
        cache.put("apps")
        assertEquals("apps", cache.getOrNull())
        assertTrue(cache.hasEntry())
    }

    @Test
    fun valueExpiresExactlyAtValidityBoundary() {
        cache.put("apps")
        now += 99L
        assertEquals("apps", cache.getOrNull())

        now += 1L // возраст == validity → устарело
        assertNull(cache.getOrNull())
        assertTrue(cache.hasEntry()) // запись есть, просто просрочена
    }

    @Test
    fun expiredEntryIsNotReturnedAfterTimeJump() {
        cache.put("apps")
        now += 24L * 60L * 60L * 1000L
        assertNull(cache.getOrNull())
    }

    @Test
    fun putRefreshesTimestamp() {
        cache.put("first")
        now += 90L
        cache.put("first") // тот же список, но время обновлено
        now += 50L
        assertEquals("first", cache.getOrNull())
    }

    @Test
    fun putReplacesValue() {
        cache.put("first")
        cache.put("second")
        assertEquals("second", cache.getOrNull())
    }

    @Test
    fun invalidateClearsEntry() {
        cache.put("apps")
        cache.invalidate()
        assertNull(cache.getOrNull())
        assertFalse(cache.hasEntry())
    }

    @Test
    fun clockGoingBackwardsDoesNotServeStaleValue() {
        // Часы устройства могут «прыгнуть» назад (синхронизация, ручная установка).
        // Отрицательный возраст — не повод считать запись свежей навсегда.
        cache.put("apps")
        now -= 10_000L
        assertNull(cache.getOrNull())
    }

    @Test
    fun zeroValidityDisablesCaching() {
        val noCache = TtlCache<String>(validityMs = 0L, clock = { now })
        noCache.put("apps")
        assertNull(noCache.getOrNull())
    }
}
