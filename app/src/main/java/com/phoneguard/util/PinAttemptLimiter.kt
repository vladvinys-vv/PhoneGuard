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
 * Логика вынесена в чистую функцию, чтобы её можно было покрыть JVM-тестами
 * без Android (см. `PinAttemptLimiterTest`).
 */
object PinAttemptLimiter {

    /** Сколько неудачных попыток допускается до блокировки. */
    const val MAX_ATTEMPTS = 5

    /** Длительность блокировки после исчерпания попыток — 15 минут. */
    const val LOCKOUT_MS = 15 * 60 * 1000L

    data class State(
        val attempts: Int = 0,
        val lockedUntil: Long = 0L
    ) {
        fun isLocked(now: Long): Boolean = now < lockedUntil

        fun remainingMs(now: Long): Long = (lockedUntil - now).coerceAtLeast(0L)
    }

    /** Успешная проверка PIN: счётчик и блокировка сбрасываются. */
    fun afterSuccess(): State = State()

    /**
     * Неудачная проверка PIN.
     *
     * @param previous состояние до попытки
     * @param now текущее время в миллисекундах
     */
    fun afterFailure(previous: State, now: Long): State {
        if (previous.isLocked(now)) return previous
        val attempts = previous.attempts + 1
        return if (attempts >= MAX_ATTEMPTS) {
            State(attempts = 0, lockedUntil = now + LOCKOUT_MS)
        } else {
            State(attempts = attempts, lockedUntil = 0L)
        }
    }
}
