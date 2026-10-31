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
    suspend fun scan(context: android.content.Context): List<IntrusionEvent> = withContext(Dispatchers.IO) {
        val events = intrusionDetector.detect(intrusionDetector.collectEnvironmentSignals(context))
        events.forEach { event ->
            intrusionEventDao.insert(
                IntrusionEventEntity(
                    timestamp = event.timestamp,
                    type = event.type,
                    severity = event.severity,
                    evidence = event.evidence.entries.joinToString(";") { "${it.key}=${it.value}" },
                    uid = event.uid,
                    packageName = event.packageName,
                    ipAddress = event.ipAddress,
                    photoPath = event.photoPath,
                    handled = event.handled
                )
            )
            val actions = intrusionResponseOrchestrator.plan(event)
            intrusionResponseExecutor.execute(event, actions)
            honeypotManager.decoyNames(event)
        }
        events
    }

    suspend fun recordEvent(event: IntrusionEvent) = withContext(Dispatchers.IO) {
        intrusionEventDao.insert(toEntity(event))
        val actions = intrusionResponseOrchestrator.plan(event)
        intrusionResponseExecutor.execute(event, actions)
    }

    fun toEntity(event: IntrusionEvent): IntrusionEventEntity =
        IntrusionEventEntity(
            id = event.id,
            timestamp = event.timestamp,
            type = event.type,
            severity = event.severity,
            evidence = event.evidence.entries.joinToString(";") { "${it.key}=${it.value}" },
            uid = event.uid,
            packageName = event.packageName,
            ipAddress = event.ipAddress,
            photoPath = event.photoPath,
            handled = event.handled
        )
}
