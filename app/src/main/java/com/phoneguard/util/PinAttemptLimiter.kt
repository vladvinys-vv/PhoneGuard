package com.phoneguard.util

/**
 * Ограничитель попыток ввода PIN-кода.
 *
 * Зачем это нужно: PIN проверяется не только на экране, но и в
 * `SmsCommandReceiver` (`#PG:PIN:LOCK`), где подбор ничем не ограничивался —
 * любой, кто может отправлять SMS на этот номер, получал бесконечное число
 * попыток (4-значный PIN перебирается за считанные минуты).
 *
 * Правило: [MAX_ATTEMPTS] неудачных попыток подряд → блокировка на
 * [LOCKOUT_MS]; успешный ввод обнуляет счётчик. Состояние хранится в
 * DataStore, поэтому перезагрузка телефона блокировку не снимает.
 *
 * Блокировка отсчитывается по двум часам сразу:
 *  * `lockedUntil` — системное время. Единственный ориентир, который
 *    переживает перезагрузку устройства;
 *  * `lockoutRemainingMs` + `lockoutElapsedMark` — монотонное
 *    `SystemClock.elapsedRealtime()`. Пока устройство не перезагружалось, оно
 *    главнее: перевести системные часы вперёд, чтобы «переждать» 15 минут,
 *    больше нельзя (раньше это был рабочий обход — подбор PIN продолжался,
 *    стоило только сменить время или дождаться синхронизации NTP).
 *
 * Логика вынесена в чистые функции, чтобы её можно было покрыть JVM-тестами
 * без Android (см. `PinAttemptLimiterTest`).
 */
object PinAttemptLimiter {

    /** Сколько неудачных попыток допускается до блокировки. */
    const val MAX_ATTEMPTS = 5

    /** Длительность блокировки после исчерпания попыток — 15 минут. */
    const val LOCKOUT_MS = 15 * 60 * 1000L

    data class State(
        val attempts: Int = 0,
        val lockedUntil: Long = 0L,
        /** Сколько блокировки оставалось в момент [lockoutElapsedMark]. */
        val lockoutRemainingMs: Long = 0L,
        /** `SystemClock.elapsedRealtime()` в момент блокировки (0 — метки нет). */
        val lockoutElapsedMark: Long = 0L
    ) {
        /**
         * Сколько ещё действует блокировка.
         *
         * @param nowWallMs системное время (`System.currentTimeMillis()`)
         * @param nowElapsedMs монотонное время (`SystemClock.elapsedRealtime()`)
         */
        fun remainingMs(nowWallMs: Long, nowElapsedMs: Long): Long {
            if (lockedUntil == 0L && lockoutRemainingMs == 0L) return 0L
            // Метка есть и монотонные часы не откатились назад ⇒ перезагрузки не было.
            val sameBoot = lockoutElapsedMark > 0L && nowElapsedMs >= lockoutElapsedMark
            val remaining = if (sameBoot) {
                lockoutRemainingMs - (nowElapsedMs - lockoutElapsedMark)
            } else {
                // После перезагрузки монотонные часы начинаются с нуля — остаётся
                // только системное время, причём его откат назад не должен
                // превращаться в бесконечную блокировку.
                lockedUntil - nowWallMs
            }
            return remaining.coerceIn(0L, LOCKOUT_MS)
        }

        fun isLocked(nowWallMs: Long, nowElapsedMs: Long): Boolean =
            remainingMs(nowWallMs, nowElapsedMs) > 0L
    }

    /** Успешная проверка PIN: счётчик и блокировка сбрасываются. */
    fun afterSuccess(): State = State()

    /**
     * Неудачная проверка PIN.
     *
     * @param previous состояние до попытки
     * @param nowWallMs текущее системное время в миллисекундах
     * @param nowElapsedMs текущее монотонное время в миллисекундах
     */
    fun afterFailure(previous: State, nowWallMs: Long, nowElapsedMs: Long): State {
        if (previous.isLocked(nowWallMs, nowElapsedMs)) return previous
        val attempts = previous.attempts + 1
        return if (attempts >= MAX_ATTEMPTS) {
            State(
                attempts = 0,
                lockedUntil = nowWallMs + LOCKOUT_MS,
                lockoutRemainingMs = LOCKOUT_MS,
                lockoutElapsedMark = nowElapsedMs
            )
        } else {
            State(attempts = attempts)
        }
    }
}
