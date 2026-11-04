package com.phoneguard.intrusion

/**
 * Антидребезг для активных реакций на вторжение.
 *
 * Повод: серия одинаковых событий (перебор PIN, обход фаервола, доступ к приманке) вызывала
 * серию одинаковых активных реакций — пять правил фаервола на один пакет, пять SMS доверенным
 * контактам, пять запусков скрытой съёмки. У приманок антидребезг был ([HoneypotReportGate]),
 * у остальных действий — нет.
 *
 * Правила:
 *  * информационные действия ([IntrusionResponsePolicy.ALWAYS_ON]) и запирание сейфа не
 *    подавляются: пользователь должен видеть каждое событие, а `lock()` идемпотентен;
 *  * активные действия (сирена, фото, SMS, блокировка пакета и сети) — не чаще одного раза
 *    на одинаковую пару «событие + действие» в [REPEAT_WINDOW_MS];
 *  * время монотонное (`SystemClock.elapsedRealtime()`): перевод системных часов не должен
 *    ни снимать защиту, ни глушить настоящую реакцию;
 *  * карта ограничена [MAX_TRACKED_REACTIONS]; при переполнении сбрасывается целиком — лучше
 *    пропустить дубликат, чем потерять новую реакцию.
 *
 * Ключ включает тип события, пакет и само действие: второе событие другого типа или другое
 * действие по тому же событию подавляться не должны.
 */
internal class IntrusionReactionGate(
    private val windowMs: Long = REPEAT_WINDOW_MS,
    private val maxEntries: Int = MAX_TRACKED_REACTIONS
) {

    private val ranAt = mutableMapOf<String, Long>()

    /**
     * @param key отпечаток «событие + действие» из [keyFor]
     * @param nowElapsedMs текущее монотонное время
     * @return `true`, если реакцию нужно выполнить
     */
    @Synchronized
    fun shouldRun(key: String, nowElapsedMs: Long): Boolean {
        val last = ranAt[key]
        if (last != null && elapsed(nowElapsedMs, last) < windowMs) return false

        if (ranAt.size >= maxEntries) {
            ranAt.entries.removeAll { (_, at) -> elapsed(nowElapsedMs, at) >= windowMs }
            if (ranAt.size >= maxEntries) ranAt.clear()
        }
        ranAt[key] = nowElapsedMs
        return true
    }

    /** Сколько отпечатков сейчас в памяти (для тестов и диагностики). */
    @Synchronized
    fun trackedCount(): Int = ranAt.size

    /** Полный сброс — например, при выключении автоответа. */
    @Synchronized
    fun reset() = ranAt.clear()

    private fun elapsed(now: Long, start: Long): Long = (now - start).coerceAtLeast(0L)

    companion object {
        /** Окно, в котором одинаковая реакция считается повтором того же события. */
        const val REPEAT_WINDOW_MS = 30_000L

        /** Предел карты: больше тридцати разных реакций в окне не бывает. */
        const val MAX_TRACKED_REACTIONS = 64

        /** Пакета нет (событие без приложения) — отдельное значение ключа, а не пустая строка. */
        const val NO_PACKAGE = "-"

        /**
         * Какие действия подавляются при повторе.
         *
         * Сирена, съёмка, SMS и блокировки — дорогие и заметные снаружи. Предупреждение и выгрузка
         * в журнал не подавляются никогда: иначе серия событий исчезла бы из журнала, а это
         * ровно та ошибка, которую исправлял `IntrusionResponsePolicy`.
         */
        val GATED: Set<IntrusionResponsePolicy.Category> = setOf(
            IntrusionResponsePolicy.Category.ALARM,
            IntrusionResponsePolicy.Category.PHOTO,
            IntrusionResponsePolicy.Category.NOTIFY_CONTACTS,
            IntrusionResponsePolicy.Category.BLOCK_PACKAGE,
            IntrusionResponsePolicy.Category.BLOCK_NETWORK
        )

        fun shouldGate(category: IntrusionResponsePolicy.Category): Boolean = category in GATED

        /** Отпечаток «тип события + пакет + действие». */
        fun keyFor(event: IntrusionEvent, action: IntrusionResponseAction): String =
            "${event.type.name}|${event.packageName ?: NO_PACKAGE}|${actionName(action)}"

        /** Стабильное имя действия: `toString()` у data-класса менялся бы вместе с данными. */
        fun actionName(action: IntrusionResponseAction): String = when (action) {
            is IntrusionResponseAction.ShowWarning -> "SHOW_WARNING"
            IntrusionResponseAction.ExportLog -> "EXPORT_LOG"
            IntrusionResponseAction.LockVault -> "LOCK_VAULT"
            is IntrusionResponseAction.BlockPackage -> "BLOCK_PACKAGE"
            is IntrusionResponseAction.BlockNetwork -> "BLOCK_NETWORK"
            IntrusionResponseAction.StartAlarm -> "START_ALARM"
            IntrusionResponseAction.CapturePhoto -> "CAPTURE_PHOTO"
            is IntrusionResponseAction.NotifyTrustedContacts -> "NOTIFY_CONTACTS"
            is IntrusionResponseAction.CreateHoneypot -> "CREATE_HONEYPOT"
        }
    }
}
