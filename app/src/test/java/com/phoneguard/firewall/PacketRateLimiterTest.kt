package com.phoneguard.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окна ограничителей сетевого горячего пути: частота записей в лог,
 * «битые» пакеты и детектор перебора портов.
 *
 * Раньше эта арифметика была размазана по [PhoneGuardVpnService] и не
 * проверялась ни одним тестом.
 */
class PacketRateLimiterTest {

    private val start = 5_000_000L

    @Test
    fun logAllowanceIsLimitedPerWindow() {
        val limiter = PacketRateLimiter()
        val allowed = (1..PacketRateLimiter.MAX_LOGS_PER_WINDOW).count { limiter.allowLog(start + it) }
        assertEquals(PacketRateLimiter.MAX_LOGS_PER_WINDOW, allowed)
        assertFalse("сверх лимита запись запрещена", limiter.allowLog(start + 20))
    }

    @Test
    fun logWindowReopensAfterOneSecond() {
        val limiter = PacketRateLimiter()
        repeat(PacketRateLimiter.MAX_LOGS_PER_WINDOW) { limiter.allowLog(start) }
        assertFalse(limiter.allowLog(start + PacketRateLimiter.LOG_WINDOW_MS))
        // Окно сдвигается только когда прошло больше секунды.
        assertTrue(limiter.allowLog(start + PacketRateLimiter.LOG_WINDOW_MS + 1))
    }

    @Test
    fun malformedPacketsReportOnlyOnThresholdOverflow() {
        val limiter = PacketRateLimiter()
        val reports = (1..PacketRateLimiter.MALFORMED_PACKET_THRESHOLD)
            .count { limiter.registerMalformedPacket(start + it) }
        assertEquals("порог ещё не превышен", 0, reports)
        assertTrue("превышение порога даёт событие", limiter.registerMalformedPacket(start + 200))
        assertFalse("счётчик сброшен после события", limiter.registerMalformedPacket(start + 201))
    }

    @Test
    fun malformedWindowExpiryPreventsFalseReport() {
        val limiter = PacketRateLimiter()
        repeat(PacketRateLimiter.MALFORMED_PACKET_THRESHOLD) { limiter.registerMalformedPacket(start) }
        val afterWindow = start + PacketRateLimiter.MALFORMED_WINDOW_MS + 1
        var reports = 0
        repeat(PacketRateLimiter.MALFORMED_PACKET_THRESHOLD) { reports += if (limiter.registerMalformedPacket(afterWindow)) 1 else 0 }
        assertEquals("новое окно начинается с нуля", 0, reports)
    }

    @Test
    fun portScanIsReportedOnlyAboveThreshold() {
        val limiter = PacketRateLimiter()
        var reports = 0
        (1..PacketRateLimiter.PORTS_PER_WINDOW).forEach { port ->
            reports += if (limiter.shouldReportPortScan(uid = 10_000, destPort = port, nowElapsedMs = start + port)) 1 else 0
        }
        assertEquals("ровно на пороге события нет", 0, reports)
        assertTrue(
            limiter.shouldReportPortScan(10_000, PacketRateLimiter.PORTS_PER_WINDOW + 1, start + 100)
        )
    }

    @Test
    fun repeatedPortsDoNotTriggerScan() {
        val limiter = PacketRateLimiter()
        repeat(500) { index ->
            assertFalse(
                "повторы одного порта — это не перебор",
                limiter.shouldReportPortScan(uid = 10_000, destPort = 443, nowElapsedMs = start + index)
            )
        }
    }

    @Test
    fun reportCooldownSuppressesRepeatedEvents() {
        val limiter = PacketRateLimiter()
        var reports = 0
        (1..500).forEach { port ->
            reports += if (limiter.shouldReportPortScan(10_000, port, start + port)) 1 else 0
        }
        assertEquals("на UID не больше одного события за 10 минут", 1, reports)
    }

    @Test
    fun reportCooldownExpires() {
        val limiter = PacketRateLimiter()
        (1..PacketRateLimiter.PORTS_PER_WINDOW + 1).forEach { port ->
            limiter.shouldReportPortScan(10_000, port, start + port)
        }
        val afterCooldown = start + 100 + PacketRateLimiter.PORT_SCAN_REPORT_COOLDOWN_MS + 1
        var second = false
        (1..PacketRateLimiter.PORTS_PER_WINDOW + 1).forEach { port ->
            if (limiter.shouldReportPortScan(10_000, port, afterCooldown + port)) second = true
        }
        assertTrue("после кулдауна событие снова возможно", second)
    }

    @Test
    fun usersAreTrackedSeparately() {
        val limiter = PacketRateLimiter()
        (1..PacketRateLimiter.PORTS_PER_WINDOW).forEach { port -> limiter.shouldReportPortScan(1, port, start + port) }
        assertFalse(
            "окно второго UID не заполнено",
            limiter.shouldReportPortScan(2, 1, start + 20)
        )
    }

    @Test
    fun portWindowExpiryStartsFreshSet() {
        val limiter = PacketRateLimiter()
        (1..PacketRateLimiter.PORTS_PER_WINDOW).forEach { port -> limiter.shouldReportPortScan(7, port, start + port) }
        val later = start + PacketRateLimiter.PORT_SCAN_WINDOW_MS * 2
        assertFalse(
            "старые порты не должны накапливаться между окнами",
            limiter.shouldReportPortScan(7, 1, later)
        )
    }

    @Test
    fun trackedUsersAreBounded() {
        val limiter = PacketRateLimiter(maxTrackedUids = 4)
        (0 until 50).forEach { index -> limiter.shouldReportPortScan(uid = index, destPort = 443, nowElapsedMs = start + index) }
        assertTrue(
            "карты UID не должны расти бесконечно (${limiter.trackedUidCount()})",
            limiter.trackedUidCount() <= 5
        )
    }

    @Test
    fun resetClearsAllWindows() {
        val limiter = PacketRateLimiter()
        repeat(PacketRateLimiter.MAX_LOGS_PER_WINDOW + 5) { limiter.allowLog(start) }
        (1..PacketRateLimiter.PORTS_PER_WINDOW + 1).forEach { port -> limiter.shouldReportPortScan(5, port, start + port) }
        limiter.reset()
        assertTrue("лимит логов сброшен", limiter.allowLog(start))
        assertEquals(0, limiter.trackedUidCount())
        assertFalse(
            "после сброса окно портов пустое",
            limiter.shouldReportPortScan(5, 1, start)
        )
    }

    @Test
    fun clockGoingBackwardsDoesNotCrashOrResetWindows() {
        val limiter = PacketRateLimiter()
        repeat(PacketRateLimiter.MAX_LOGS_PER_WINDOW + 5) { limiter.allowLog(start) }
        // Перезагрузка: монотонные часы начинаются заново.
        assertFalse("откат часов не должен открывать окно заново", limiter.allowLog(1_000))
    }
}
