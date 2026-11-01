package com.phoneguard.firewall

/**
 * Счётчики сетевых окон для горячего пути VPN.
 *
 * Раньше три независимых ограничителя жили прямо в [PhoneGuardVpnService]
 * полями сервиса: счётчик записей в лог, счётчик «битых» пакетов и детектор
 * перебора портов. Из этого вытекали две проблемы:
 *
 *  * окна считались по `System.currentTimeMillis()`, поэтому перевод системных
 *    часов (в том числе автоматическая синхронизация по NTP) сбрасывал окна и
 *    разрешал всплеск записей/событий;
 *  * поля менялись из нескольких потоков обработки пакетов без синхронизации,
 *    так что лимиты «плыли».
 *
 * Здесь всё собрано в один класс, который работает только с переданным
 * монотонным временем (`SystemClock.elapsedRealtime()`) и синхронизирует
 * доступ. Логика не зависит от Android и покрыта `PacketRateLimiterTest`.
 */
class PacketRateLimiter(
    private val maxLogsPerWindow: Int = MAX_LOGS_PER_WINDOW,
    private val logWindowMs: Long = LOG_WINDOW_MS,
    private val malformedThreshold: Int = MALFORMED_PACKET_THRESHOLD,
    private val malformedWindowMs: Long = MALFORMED_WINDOW_MS,
    private val portsPerWindow: Int = PORTS_PER_WINDOW,
    private val portScanWindowMs: Long = PORT_SCAN_WINDOW_MS,
    private val portScanCooldownMs: Long = PORT_SCAN_REPORT_COOLDOWN_MS,
    private val maxTrackedUids: Int = MAX_TRACKED_UIDS
) {

    private var logCount = 0
    private var logWindowStart = NO_WINDOW

    private var malformedCount = 0
    private var malformedWindowStart = NO_WINDOW

    private val portsByUid = mutableMapOf<Int, MutableSet<Int>>()
    private val portWindowStart = mutableMapOf<Int, Long>()
    private val lastReportAt = mutableMapOf<Int, Long>()

    /**
     * Учитывает заблокированный пакет и сообщает, можно ли писать его в лог.
     *
     * @return `true`, если запись разрешена (не больше [maxLogsPerWindow] в окно)
     */
    @Synchronized
    fun allowLog(nowElapsedMs: Long): Boolean {
        if (logWindowStart == NO_WINDOW || elapsed(nowElapsedMs, logWindowStart) > logWindowMs) {
            logCount = 0
            logWindowStart = nowElapsedMs
        }
        logCount++
        return logCount <= maxLogsPerWindow
    }

    /**
     * Учитывает пакет, который не удалось разобрать.
     *
     * @return `true`, если превышен порог [malformedThreshold] за окно — нужно
     *   записать событие вторжения
     */
    @Synchronized
    fun registerMalformedPacket(nowElapsedMs: Long): Boolean {
        if (malformedWindowStart == NO_WINDOW || elapsed(nowElapsedMs, malformedWindowStart) > malformedWindowMs) {
            malformedCount = 0
            malformedWindowStart = nowElapsedMs
        }
        malformedCount++
        if (malformedCount > malformedThreshold) {
            malformedCount = 0
            return true
        }
        return false
    }

    /**
     * Учитывает подключение к порту и сообщает, похоже ли это на перебор портов.
     *
     * Событие создаётся не чаще одного раза в [portScanCooldownMs] на UID, а
     * окно наблюдения — [portScanWindowMs]: больше [portsPerWindow] разных
     * портов за окно.
     */
    @Synchronized
    fun shouldReportPortScan(uid: Int, destPort: Int, nowElapsedMs: Long): Boolean {
        trimUidMaps()

        val lastReport = lastReportAt[uid]
        if (lastReport != null && elapsed(nowElapsedMs, lastReport) < portScanCooldownMs) return false

        val windowStart = portWindowStart[uid]
        if (windowStart == null || elapsed(nowElapsedMs, windowStart) > portScanWindowMs) {
            portsByUid[uid] = mutableSetOf()
            portWindowStart[uid] = nowElapsedMs
        }
        val ports = portsByUid.getOrPut(uid) { mutableSetOf() }
        ports += destPort
        if (ports.size <= portsPerWindow) return false

        ports.clear()
        portWindowStart[uid] = nowElapsedMs
        lastReportAt[uid] = nowElapsedMs
        trimUidMaps()
        return true
    }

    /** Сбрасывает все окна — вызывается при перезапуске туннеля. */
    @Synchronized
    fun reset() {
        logCount = 0
        logWindowStart = NO_WINDOW
        malformedCount = 0
        malformedWindowStart = NO_WINDOW
        portsByUid.clear()
        portWindowStart.clear()
        lastReportAt.clear()
    }

    /** Сколько UID сейчас отслеживается (для тестов и диагностики). */
    @Synchronized
    fun trackedUidCount(): Int = maxOf(portsByUid.size, portWindowStart.size, lastReportAt.size)

    /**
     * Защита от роста карт: UID-ов в системе ограниченное число, но после
     * переустановок и «двойников» работающих профилей набор может расти.
     * При выходе за [maxTrackedUids] карты очищаются целиком — это дешевле и
     * безопаснее, чем хранить их бесконечно.
     */
    private fun trimUidMaps() {
        val size = maxOf(portsByUid.size, portWindowStart.size, lastReportAt.size)
        if (size <= maxTrackedUids) return
        portsByUid.clear()
        portWindowStart.clear()
        lastReportAt.clear()
    }

    /** Монотонные часы могут откатиться только при перезагрузке — тогда окно считаем новым. */
    private fun elapsed(now: Long, start: Long): Long = (now - start).coerceAtLeast(0L)

    companion object {
        /** Значение «окно ещё не открывалось». */
        private const val NO_WINDOW = Long.MIN_VALUE

        /** Лимит записей в лог за окно. */
        const val MAX_LOGS_PER_WINDOW = 10

        /** Окно лимита записей в лог — одна секунда. */
        const val LOG_WINDOW_MS = 1_000L

        /** Порог «битых» пакетов за окно. */
        const val MALFORMED_PACKET_THRESHOLD = 100

        /** Окно подсчёта «битых» пакетов. */
        const val MALFORMED_WINDOW_MS = 10_000L

        /** Сколько разных портов за окно считается «перебором». */
        const val PORTS_PER_WINDOW = 60

        /** Окно наблюдения за портами. */
        const val PORT_SCAN_WINDOW_MS = 10_000L

        /** Не чаще одного события о переборе портов на UID. */
        const val PORT_SCAN_REPORT_COOLDOWN_MS = 10 * 60_000L

        /** Верхняя граница числа отслеживаемых UID. */
        const val MAX_TRACKED_UIDS = 256
    }
}
