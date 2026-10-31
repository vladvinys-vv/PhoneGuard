package com.phoneguard.intrusion

import com.phoneguard.data.local.IntrusionEventDao
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class IntrusionRepositoryTest {
    @Test
    fun `toEntity preserves type and severity`() {
        val repository = IntrusionRepository(
            mockk(relaxed = true),
            IntrusionDetector(),
            IntrusionResponseOrchestrator(),
            HoneypotManager(),
            mockk(relaxed = true)
        )
        val entity = repository.toEntity(
            IntrusionEvent(type = IntrusionType.BRUTE_FORCE, severity = IntrusionSeverity.CRITICAL)
        )
        assertEquals(IntrusionType.BRUTE_FORCE, entity.type)
        assertEquals(IntrusionSeverity.CRITICAL, entity.severity)
    }
}
