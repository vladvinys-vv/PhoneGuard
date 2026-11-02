package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замер по монотонным часам.
 *
 * Раньше `PerformanceMonitor` считал длительность через
 * `System.currentTimeMillis()`, поэтому перевод системного времени во время
 * сканирования давал отрицательную длительность в логе.
 */
class ElapsedTimerTest {

    private var now = 1_000L
    private val timer = ElapsedTimer { now }

    @Test
    fun `длительность считается по переданным часам`() {
        timer.start()
        now += 2_500L
        assertEquals(2_500L, timer.stop())
        assertEquals(2_500L, timer.durationMs())
    }

    @Test
    fun `stop без start возвращает null, а не нулевую длительность`() {
        assertNull(timer.stop())
        assertEquals(0L, timer.durationMs())
    }

    @Test
    fun `повторный stop не перезаписывает результат`() {
        timer.start()
        now += 700L
        assertEquals(700L, timer.stop())
        now += 5_000L
        assertNull(timer.stop())
        assertEquals(700L, timer.durationMs())
    }

    @Test
    fun `откат часов не даёт отрицательную длительность`() {
        // Перезагрузка/сброс монотонных часов посреди замера.
        timer.start()
        now -= 10_000L
        assertEquals(0L, timer.stop())
    }

    @Test
    fun `состояние замера видно снаружи`() {
        assertFalse(timer.isRunning())
        timer.start()
        assertTrue(timer.isRunning())
        timer.stop()
        assertFalse(timer.isRunning())
    }

    @Test
    fun `start перезапускает замер`() {
        timer.start()
        now += 100L
        timer.start()
        now += 40L
        assertEquals(40L, timer.stop())
    }

    @Test
    fun `reset обнуляет и состояние, и последнюю длительность`() {
        timer.start()
        now += 10L
        timer.stop()
        timer.reset()
        assertEquals(0L, timer.durationMs())
        assertNull(timer.stop())
    }
}
