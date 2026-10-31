package com.phoneguard.intrusion

/**
 * Правило отбора новых событий сканирования окружения.
 *
 * Сканирование выполняется на каждом старте приложения, а сигналы окружения
 * (эмулятор, включённый ADB, debug-сборка, сторонняя служба доступности) при этом
 * не меняются. Раньше каждое сканирование вставляло их заново и запускало повторную
 * реакцию; теперь событие считается новым, только если такого же (тип + доказательства)
 * не было за окно [DEDUP_WINDOW_MS].
 *
 * Вынесено в чистую функцию, чтобы покрыть правилом JVM-тестами.
 */
internal object IntrusionScanPolicy {

    /** Окно, в котором повторный такой же сигнал считается уже известным. */
    const val DEDUP_WINDOW_MS = 24 * 60 * 60 * 1000L

    fun selectNewEvents(
        detected: List<IntrusionEvent>,
        recent: List<IntrusionEvent>
    ): List<IntrusionEvent> {
        val known = recent.map { it.type to it.evidence }.toSet()
        return detected
            .filterNot { event -> (event.type to event.evidence) in known }
            .distinctBy { event -> event.type to event.evidence }
    }
}
