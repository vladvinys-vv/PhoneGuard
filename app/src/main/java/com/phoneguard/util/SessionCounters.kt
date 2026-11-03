package com.phoneguard.util

/**
 * Счётчики событий за сессию — потокобезопасные и без Android.
 *
 * Вынесено из [AnalyticsHelper]: там карта счётчиков и `synchronized` жили в классе с `Context`,
 * `BuildConfig` и `Log`, поэтому «счётчики не теряют события при параллельной записи» нельзя было
 * проверить. Отдельный класс позволяет проверить это настоящими потоками.
 */
internal class SessionCounters {

    private val counts = mutableMapOf<String, Int>()

    /** Увеличивает счётчик события. Пустое имя игнорируется: это заведомо ошибка вызывающего. */
    @Synchronized
    fun record(eventName: String) {
        if (eventName.isBlank()) return
        counts[eventName] = (counts[eventName] ?: 0) + 1
    }

    /** Сколько раз событие произошло за сессию (0, если не происходило). */
    @Synchronized
    fun count(eventName: String): Int = counts[eventName] ?: 0

    /** Снимок всех счётчиков — копия, чтобы читатель не мог испортить состояние. */
    @Synchronized
    fun snapshot(): Map<String, Int> = HashMap(counts)

    /** Общее число событий по всем именам. */
    @Synchronized
    fun total(): Int = counts.values.sum()
}
