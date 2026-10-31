package com.phoneguard.intrusion

import com.phoneguard.data.local.IntrusionEventDao
import com.phoneguard.data.local.IntrusionEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntrusionRepository @Inject constructor(
    private val intrusionEventDao: IntrusionEventDao,
    private val intrusionDetector: IntrusionDetector,
    private val intrusionResponseOrchestrator: IntrusionResponseOrchestrator,
    private val honeypotManager: HoneypotManager,
    private val intrusionResponseExecutor: IntrusionResponseExecutor
) {
    /**
     * Сканирует окружение и записывает **только новые** события.
     *
     * Раньше каждое сканирование (а оно выполняется на каждом старте приложения)
     * заново вставляло те же самые сигналы — эмулятор, включённый ADB, debug-сборка —
     * и запускало по ним реакцию (тревога, фото, SMS). Теперь сигнал, который уже
     * зафиксирован за последние сутки (тот же тип + те же доказательства),
     * пропускается.
     */
    suspend fun scan(context: android.content.Context): List<IntrusionEvent> = withContext(Dispatchers.IO) {
        val detected = intrusionDetector.detect(intrusionDetector.collectEnvironmentSignals(context))
        val since = System.currentTimeMillis() - IntrusionScanPolicy.DEDUP_WINDOW_MS
        val recent = intrusionEventDao.getSince(since).map { it.toIntrusionEvent() }
        val events = IntrusionScanPolicy.selectNewEvents(detected, recent)

        events.forEach { event ->
            intrusionEventDao.insert(event.toEntity())
            val actions = intrusionResponseOrchestrator.plan(event)
            intrusionResponseExecutor.execute(event, actions)
            // Приманки действительно создаются (раньше здесь считались только имена,
            // и файлы не появлялись вовсе).
            honeypotManager.createDecoys(context, event)
        }
        events
    }

    suspend fun recordEvent(event: IntrusionEvent) = withContext(Dispatchers.IO) {
        intrusionEventDao.insert(toEntity(event))
        val actions = intrusionResponseOrchestrator.plan(event)
        intrusionResponseExecutor.execute(event, actions)
    }

    fun toEntity(event: IntrusionEvent): IntrusionEventEntity = event.toEntity()
}
