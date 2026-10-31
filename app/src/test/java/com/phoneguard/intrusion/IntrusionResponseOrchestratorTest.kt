package com.phoneguard.intrusion

import org.junit.Assert.assertTrue
import org.junit.Test

class IntrusionResponseOrchestratorTest {
    @Test
    fun `high severity plans alarm and photo`() {
        val orchestrator = IntrusionResponseOrchestrator()
        val event = IntrusionEvent(type = IntrusionType.RAT_DETECTED, severity = IntrusionSeverity.HIGH)
        val actions = orchestrator.plan(event)
        assertTrue(actions.any { it is IntrusionResponseAction.StartAlarm })
        assertTrue(actions.any { it is IntrusionResponseAction.CapturePhoto })
        assertTrue(actions.any { it is IntrusionResponseAction.LockVault })
    }

    @Test
    fun `critical severity plans warning`() {
        val orchestrator = IntrusionResponseOrchestrator()
        val event = IntrusionEvent(type = IntrusionType.RAT_DETECTED, severity = IntrusionSeverity.CRITICAL)
        val actions = orchestrator.plan(event)
        assertTrue(actions.any { it is IntrusionResponseAction.ShowWarning })
    }
}
