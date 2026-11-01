package com.phoneguard.intrusion

/**
 * Антидребезг для событий доступа к файлам-приманкам.
 *
 * Раньше эта логика жила внутри [IntrusionResponseExecutor] и опиралась на
 * `System.currentTimeMillis()`: перевод системных часов вперёд снимал
 * «карантин» и порождал повторные тревоги, а откат назад — наоборот, глушил
 * настоящие события. Кроме того, её нельзя было проверить тестом.
 *
 * Правила:
 *  * файл, изменённый меньше [selfWriteWindowMs] назад, считается собственной
 *    записью приманки — событие не создаётся;
 *  * по одному пути — не чаще одного события в [reportIntervalMs];
 *  * карта путей ограничена [maxEntries], чтобы не расти месяцами.
 *
 * Интервалы считаются по монотонным часам (`SystemClock.elapsedRealtime()`),
 * а «свежесть файла» — по системному времени (`File.lastModified()`), потому
 * что mtime файла берётся из тех же часов, что и `currentTimeMillis()`.
 */
class HoneypotReportGate(
    private val selfWriteWindowMs: Long = SELF_WRITE_WINDOW_MS,
    private val reportIntervalMs: Long = REPORT_INTERVAL_MS,
    private val maxEntries: Int = MAX_TRACKED_PATHS
) {

    private val reportedAt = mutableMapOf<String, Long>()

    /**
     * @param path путь приманки
     * @param lastModifiedWallMs `File.lastModified()` этого файла
     * @param nowWallMs текущее системное время
     * @param nowElapsedMs текущее монотонное время
     * @return `true`, если о доступе стоит сообщать
     */
    @Synchronized
    fun shouldReport(
        path: String,
        lastModifiedWallMs: Long,
        nowWallMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        // Свои записи приманок наблюдатель тоже видит — их не считаем атакой.
        if (nowWallMs - lastModifiedWallMs < selfWriteWindowMs) return false

        val last = reportedAt[path]
        if (last != null && elapsed(nowElapsedMs, last) < reportIntervalMs) return false

        if (reportedAt.size >= maxEntries) {
            reportedAt.entries.removeAll { (_, at) -> elapsed(nowElapsedMs, at) >= reportIntervalMs }
            if (reportedAt.size >= maxEntries) reportedAt.clear()
        }
        reportedAt[path] = nowElapsedMs
        return true
    }

    /** Сколько путей сейчас в карте антидребезга (для тестов и диагностики). */
    @Synchronized
    fun trackedPathCount(): Int = reportedAt.size

    /** Полный сброс — при выключении защиты. */
    @Synchronized
    fun reset() = reportedAt.clear()

    private fun elapsed(now: Long, start: Long): Long = (now - start).coerceAtLeast(0L)

    companion object {
        /** Файл, изменённый менее 5 секунд назад, считаем своей записью. */
        const val SELF_WRITE_WINDOW_MS = 5_000L

        /** Не больше одного события на один файл-приманку за 10 минут. */
        const val REPORT_INTERVAL_MS = 10 * 60_000L

        /** Предел карты антидребезга. */
        const val MAX_TRACKED_PATHS = 200
    }
}
