package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Антидребезг событий о доступе к приманкам: свои записи не считаются атакой,
 * по одному пути — одно событие в 10 минут, карта не растёт бесконечно.
 */
class HoneypotReportGateTest {

    private val wall = 1_700_000_000_000L
    private val up = 100_000L
    private val path = "/data/data/com.phoneguard/honeypot/1234_a.txt"

    @Test
    fun ownRecentWriteIsNotReported() {
        val gate = HoneypotReportGate()
        // Приманка записана прямо сейчас — это наша собственная запись.
        assertFalse(gate.shouldReport(path, lastModifiedWallMs = wall, nowWallMs = wall + 1_000L, nowElapsedMs = up))
        assertEquals(0, gate.trackedPathCount())
    }

    @Test
    fun oldFileIsReportedOnce() {
        val gate = HoneypotReportGate()
        assertTrue(gate.shouldReport(path, wall, wall + 60_000L, up))
        assertFalse("повтор в пределах 10 минут подавляется", gate.shouldReport(path, wall, wall + 60_001L, up + 1_000L))
    }

    @Test
    fun differentPathsAreReportedIndependently() {
        val gate = HoneypotReportGate()
        assertTrue(gate.shouldReport(path, wall, wall + 60_000L, up))
        assertTrue(gate.shouldReport("$path.evil", wall, wall + 60_000L, up + 10L))
        assertEquals(2, gate.trackedPathCount())
    }

    @Test
    fun reportRepeatsAfterInterval() {
        val gate = HoneypotReportGate()
        assertTrue(gate.shouldReport(path, wall, wall + 60_000L, up))
        val afterInterval = up + HoneypotReportGate.REPORT_INTERVAL_MS
        assertTrue(gate.shouldReport(path, wall, wall + 60_000L, afterInterval))
    }

    @Test
    fun movingWallClockForwardDoesNotBypassDedup() {
        val gate = HoneypotReportGate()
        assertTrue(gate.shouldReport(path, wall, wall + 60_000L, up))
        // Часы «уехали» на час вперёд, но по монотонным часам прошла секунда.
        val wallLater = wall + 60 * 60 * 1000L
        assertFalse(gate.shouldReport(path, wall, wallLater, up + 1_000L))
    }

    @Test
    fun movingWallClockBackwardDoesNotSuppressRealEvent() {
        val gate = HoneypotReportGate()
        assertTrue(gate.shouldReport(path, wall, wall + 60_000L, up))
        // Часы откатили на сутки назад: файл всё равно «старый» относительно
        // собственного mtime, а интервал антидребезга считается по аптайму.
        val wallEarlier = wall - 24 * 60 * 60 * 1000L
        assertTrue(
            "по монотонным часам интервал истёк",
            gate.shouldReport(
                path,
                lastModifiedWallMs = wallEarlier - 60_000L,
                nowWallMs = wallEarlier,
                nowElapsedMs = up + HoneypotReportGate.REPORT_INTERVAL_MS
            )
        )
    }

    @Test
    fun mapIsBounded() {
        val gate = HoneypotReportGate(maxEntries = 3)
        repeat(50) { index ->
            gate.shouldReport("/decoy/$index", wall, wall + 60_000L, up + index)
        }
        assertTrue("карта антидребезга не должна расти бесконечно", gate.trackedPathCount() <= 3)
    }

    @Test
    fun expiredEntriesAreEvictedBeforeClearingEverything() {
        val gate = HoneypotReportGate(reportIntervalMs = 1_000L, maxEntries = 2)
        gate.shouldReport("/a", wall, wall + 60_000L, up)
        gate.shouldReport("/b", wall, wall + 60_000L, up)
        // Через интервал записи устарели — при добавлении третьей они вычищаются.
        assertTrue(gate.shouldReport("/c", wall, wall + 60_000L, up + 2_000L))
        assertEquals(1, gate.trackedPathCount())
    }

    @Test
    fun resetClearsState() {
        val gate = HoneypotReportGate()
        gate.shouldReport(path, wall, wall + 60_000L, up)
        gate.reset()
        assertEquals(0, gate.trackedPathCount())
        assertTrue("после сброса событие снова разрешено", gate.shouldReport(path, wall, wall + 60_000L, up))
    }
}
