package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Счётчики событий сессии.
 *
 * События пишутся из разных потоков (детекторы, сервисы, UI), поэтому счётчики обязаны быть
 * потокобезопасными: потерянное событие — это неверная диагностика. Проверяется настоящими
 * потоками, а не рассуждением о `synchronized`.
 */
class SessionCountersTest {

    @Test
    fun unknownEventCountsAsZero() {
        assertEquals(0, SessionCounters().count("never"))
    }

    @Test
    fun recordIncrementsOnlyItsOwnName() {
        val counters = SessionCounters()
        counters.record("call_blocked")
        counters.record("call_blocked")
        counters.record("scan_completed")

        assertEquals(2, counters.count("call_blocked"))
        assertEquals(1, counters.count("scan_completed"))
        assertEquals(0, counters.count("vault_import"))
        assertEquals(3, counters.total())
    }

    @Test
    fun blankNamesAreIgnored() {
        // Пустое имя — ошибка вызывающего; копить такие «события» бессмысленно.
        val counters = SessionCounters()
        counters.record("")
        counters.record("   ")

        assertEquals(0, counters.total())
        assertTrue(counters.snapshot().isEmpty())
    }

    @Test
    fun snapshotIsACopy() {
        val counters = SessionCounters()
        counters.record("scan_completed")

        val snapshot = counters.snapshot() as? MutableMap<String, Int>
        assertFalse(snapshot == null)

        // Изменение снимка не должно влиять на живые счётчики.
        snapshot?.put("scan_completed", 999)
        assertEquals(1, counters.count("scan_completed"))
    }

    @Test
    fun concurrentRecordsAreNotLost() {
        val threads = 8
        val perThread = 2_000
        val counters = SessionCounters()
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    repeat(perThread) { counters.record("call_blocked") }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue("потоки не завершились", pool.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, counters.count("call_blocked"))
        assertEquals(threads * perThread, counters.total())
    }
}
