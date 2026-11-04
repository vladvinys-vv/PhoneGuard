package com.phoneguard.threatintel

/**
 * Когда обновлять публичный фид фишинговых URL.
 *
 * Настройки хранились парой «время последней загрузки + арифметика при проверке», и это давало
 * две проблемы:
 *
 *  * **параллельные загрузки.** Признак «фид свежий» проверялся **вне блокировки**, а время
 *    загрузки записывалось только после неё. Несколько одновременных проверок хостов видели
 *    устаревший фид и каждая тянула его заново — до 8 МБ на мобильном трафике за раз;
 *  * **стеновые часы.** Время считалось по `System.currentTimeMillis()`: перевод часов назад
 *    держал фид «свежим» бесконечно, вперёд — сбрасывал его без причины. Теперь время
 *    монотонное (`SystemClock.elapsedRealtime()`), а вся арифметика — здесь, а не в репозитории.
 *
 * Правила чистые: решение зависит только от моментов времени и интервалов.
 */
internal object ThreatFeedRefreshPolicy {

    /** Что делать при очередной проверке хоста. */
    enum class Decision {
        /** Пора загружать фид. */
        ATTEMPT,

        /** Ещё рано — используем то, что уже есть. */
        WAIT
    }

    /** Свежий фид живёт шесть часов. */
    const val FEED_TTL_MS = 6 * 60 * 60 * 1000L

    /** Пустой ответ кэшируем как неудачу: повтор через час, а не через шесть. */
    const val EMPTY_FEED_RETRY_MS = 60 * 60 * 1000L

    /** Ошибка сети: повтор раньше, чтобы не остаться без фида весь срок жизни TTL. */
    const val FAILED_FETCH_RETRY_MS = 10 * 60 * 1000L

    fun decide(nowElapsedMs: Long, nextAttemptElapsedMs: Long): Decision =
        if (nowElapsedMs >= nextAttemptElapsedMs) Decision.ATTEMPT else Decision.WAIT

    /** Успешная загрузка: следующая попытка через [ttlMs]. */
    fun nextAttemptAfterSuccess(nowElapsedMs: Long, ttlMs: Long = FEED_TTL_MS): Long =
        nowElapsedMs + ttlMs

    /** Пустой ответ: фид не обновлён, но и «свежим» он не стал. */
    fun nextAttemptAfterEmpty(nowElapsedMs: Long, retryMs: Long = EMPTY_FEED_RETRY_MS): Long =
        nowElapsedMs + retryMs

    /** Исключение при загрузке: короткая пауза перед повтором. */
    fun nextAttemptAfterFailure(nowElapsedMs: Long, retryMs: Long = FAILED_FETCH_RETRY_MS): Long =
        nowElapsedMs + retryMs
}
