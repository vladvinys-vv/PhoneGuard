package com.phoneguard.util

/**
 * Сколько раз и с какой задержкой повторять операцию, которая не удалась.
 *
 * Поводом стал детектор подглядывания: при любой ошибке камеры сервис либо продолжал «работать»
 * с нерабочей камерой, либо сразу выключался и снимал флаг функции. Ни то, ни другое неверно:
 * «камера занята другим приложением» проходит само через пару секунд, а «нет разрешения» —
 * не проходит никогда, и повторять его бессмысленно (только мигать переключателем в интерфейсе).
 *
 * Правила:
 *  - постоянная ошибка (нет разрешения, нет фронтальной камеры) — не повторяем вообще;
 *  - временная — до [MAX_ATTEMPTS] попыток с экспоненциальной задержкой;
 *  - задержка растёт от [INITIAL_DELAY_MS] и упирается в [MAX_DELAY_MS], чтобы ожидание не
 *    расползалось на минуты.
 */
internal object RetryBackoffPolicy {

    /** Всего попыток, включая первую. */
    const val MAX_ATTEMPTS = 3

    /** Задержка перед вторым заходом. */
    const val INITIAL_DELAY_MS = 1_000L

    /** Задержка не превышает этого значения. */
    const val MAX_DELAY_MS = 15_000L

    /** Характер ошибки: стоит ли вообще пробовать ещё раз. */
    enum class FailureKind {
        /** Камера занята, провайдер ещё инициализируется — повторить имеет смысл. */
        TRANSIENT,

        /** Нет разрешения, нет нужной камеры — повтор не поможет. */
        PERMANENT
    }

    /**
     * @param delaysBetweenAttempts сколько задержек уже выдержано (0 — первая неудача)
     */
    data class Decision(
        val shouldRetry: Boolean,
        val delayMs: Long
    ) {
        companion object {
            val STOP = Decision(shouldRetry = false, delayMs = 0L)
        }
    }

    /**
     * Решение по очередной неудаче.
     *
     * @param failuresSoFar сколько неудач уже случилось (0 — только что упало впервые)
     * @param kind характер ошибки
     */
    fun decide(failuresSoFar: Int, kind: FailureKind): Decision = when {
        kind == FailureKind.PERMANENT -> Decision.STOP
        failuresSoFar < 0 -> Decision.STOP
        // MAX_ATTEMPTS попыток = (MAX_ATTEMPTS - 1) повторов после первой неудачи.
        failuresSoFar >= MAX_ATTEMPTS - 1 -> Decision.STOP
        else -> Decision(shouldRetry = true, delayMs = delayFor(failuresSoFar + 1))
    }

    /**
     * Задержка перед повтором номер [attempt] (1 — первый повтор).
     *
     * Экспонента без `pow`: степень двойки ограничена [MAX_ATTEMPTS], поэтому переполнения нет.
     */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return INITIAL_DELAY_MS
        var delay = INITIAL_DELAY_MS
        repeat((attempt - 1).coerceAtMost(16)) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }
}
