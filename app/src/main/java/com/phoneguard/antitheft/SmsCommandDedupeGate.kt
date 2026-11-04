package com.phoneguard.antitheft

/**
 * Антидубликат для входящих SMS-команд.
 *
 * Многочастные SMS уже собираются в одно сообщение, но одно и то же SMS всё равно приходит
 * дважды: оператор повторяет доставку при потере подтверждения, некоторые прошивки отдают
 * `SMS_RECEIVED` и `SMS_DELIVER`, а приложение может получить бродкаст после перезапуска
 * процесса. Каждое такое повторение — это **второе выполнение** команды: вторая тревога,
 * второй кадр с камеры, повторный `LOCATION` и лишние SMS владельцу.
 *
 * Правило: текст команды с тем же отправителем не обрабатывается повторно в течение
 * [DUPLICATE_WINDOW_MS]. Окно короткое — владелец, который сознательно шлёт одну и ту же
 * команду второй раз, подождёт десять секунд.
 *
 * Время — монотонное (`SystemClock.elapsedRealtime()`): перевод системных часов не должен
 * ни снимать защиту, ни глушить настоящую команду.
 */
internal class SmsCommandDedupeGate(
    private val windowMs: Long = DUPLICATE_WINDOW_MS,
    private val maxEntries: Int = MAX_TRACKED_COMMANDS
) {

    private val seenAt = mutableMapOf<String, Long>()

    /**
     * @param fingerprint отправитель + текст команды
     * @param nowElapsedMs текущее монотонное время
     * @return `true`, если команду нужно обработать
     */
    @Synchronized
    fun shouldProcess(fingerprint: String, nowElapsedMs: Long): Boolean {
        val last = seenAt[fingerprint]
        if (last != null && elapsed(nowElapsedMs, last) < windowMs) return false

        if (seenAt.size >= maxEntries) {
            seenAt.entries.removeAll { (_, at) -> elapsed(nowElapsedMs, at) >= windowMs }
            // Карта всё ещё полна — значит в окне больше команд, чем мы готовы помнить.
            // Сбрасываем целиком: лучше пропустить дубликат, чем потерять новую команду.
            if (seenAt.size >= maxEntries) seenAt.clear()
        }
        seenAt[fingerprint] = nowElapsedMs
        return true
    }

    /** Сколько отпечатков сейчас в памяти (для тестов и диагностики). */
    @Synchronized
    fun trackedCount(): Int = seenAt.size

    /** Полный сброс — например, при выключении защиты. */
    @Synchronized
    fun reset() = seenAt.clear()

    private fun elapsed(now: Long, start: Long): Long = (now - start).coerceAtLeast(0L)

    companion object {
        /** Окно, в котором одинаковое SMS считается повторной доставкой. */
        const val DUPLICATE_WINDOW_MS = 10_000L

        /** Предел карты: больше команд в десятисекундном окне не бывает. */
        const val MAX_TRACKED_COMMANDS = 32
    }
}
