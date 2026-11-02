package com.phoneguard.util

/**
 * Замер длительности операции по монотонным часам.
 *
 * Раньше `PerformanceMonitor.Trace` считал `System.currentTimeMillis() - startedAt`.
 * Системные часы — не таймер: их двигает NTP-синхронизация, смена часового пояса
 * (на некоторых прошивках) и сам пользователь. Измерение длинного сканирования могло
 * дать отрицательную длительность или «−3 часа» в логе — ровно та же ошибка, что уже
 * была исправлена в детекторе подмены SIM и в проверке подписей APK.
 *
 * Часы передаются функцией, поэтому класс проверяется JVM-тестом без Android
 * (на устройстве передаётся `SystemClock.elapsedRealtime()`).
 */
class ElapsedTimer(private val clock: () -> Long) {

    private var startedAt: Long? = null
    private var lastDurationMs: Long = 0L

    /** Начинает замер; повторный вызов перезапускает его. */
    fun start() {
        startedAt = clock()
    }

    /**
     * Завершает замер и возвращает длительность в миллисекундах.
     *
     * `null` — если [start] не вызывался (или замер уже завершён): молчаливая
     * длительность 0 маскировала бы забытый `start()`.
     */
    fun stop(): Long? {
        val start = startedAt ?: return null
        startedAt = null
        // Отрицательное значение возможно только при откате монотонных часов
        // (перезагрузка посреди замера) — такую длительность считаем нулевой.
        lastDurationMs = (clock() - start).coerceAtLeast(0L)
        return lastDurationMs
    }

    fun isRunning(): Boolean = startedAt != null

    /** Длительность последнего завершённого замера. */
    fun durationMs(): Long = lastDurationMs

    fun reset() {
        startedAt = null
        lastDurationMs = 0L
    }
}
