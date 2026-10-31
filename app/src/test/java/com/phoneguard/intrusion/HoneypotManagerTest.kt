package com.phoneguard.intrusion

import org.junit.Assert.assertTrue
import org.junit.Test

class HoneypotManagerTest {
    @Test
    fun `decoy names contain event timestamp`() {
        val names = HoneypotManager().decoyNames(IntrusionEvent(type = IntrusionType.RAT_DETECTED, severity = IntrusionSeverity.HIGH, timestamp = 42L))
        assertTrue(names.all { it.startsWith("42_") })
    }

    @Test
    fun `decoy access detection works`() {
        assertTrue(HoneypotManager().isDecoyAccess("x/private_photos"))
    }
}
