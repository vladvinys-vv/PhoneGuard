package com.phoneguard.intrusion

import com.phoneguard.data.local.IntrusionEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class IntrusionEventEntityExtTest {
    @Test
    fun `round trips event`() {
        val entity = IntrusionEventEntity(
            id = 7,
            timestamp = 42,
            type = IntrusionType.RAT_DETECTED,
            severity = IntrusionSeverity.HIGH,
            evidence = "a=1;b=2",
            uid = 10,
            packageName = "evil",
            ipAddress = "1.2.3.4",
            photoPath = "/tmp/p.jpg",
            handled = true
        )
        val event = entity.toIntrusionEvent()
        assertEquals(IntrusionType.RAT_DETECTED, event.type)
        assertEquals(IntrusionSeverity.HIGH, event.severity)
        assertEquals("evil", event.packageName)
        assertEquals("/tmp/p.jpg", event.photoPath)
    }
}
