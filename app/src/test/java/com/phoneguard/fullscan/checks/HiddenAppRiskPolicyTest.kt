package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Приоритет причин попадания «спрятанного» приложения в отчёт. */
class HiddenAppRiskPolicyTest {

    @Test
    fun `подозрительное имя и опасные разрешения`() {
        assertEquals(
            HiddenAppRiskPolicy.Reason.SUSPICIOUS_WITH_PERMISSIONS,
            HiddenAppRiskPolicy.reason(suspiciousName = true, dangerousPermissionCount = 3)
        )
    }

    @Test
    fun `только подозрительное имя`() {
        assertEquals(
            HiddenAppRiskPolicy.Reason.SUSPICIOUS_NAME,
            HiddenAppRiskPolicy.reason(suspiciousName = true, dangerousPermissionCount = 0)
        )
    }

    @Test
    fun `только опасные разрешения`() {
        assertEquals(
            HiddenAppRiskPolicy.Reason.PERMISSIONS_ONLY,
            HiddenAppRiskPolicy.reason(suspiciousName = false, dangerousPermissionCount = 2)
        )
    }

    @Test
    fun `ни имени, ни разрешений — показывать нечего`() {
        assertFalse(
            HiddenAppRiskPolicy.isReportable(suspiciousName = false, dangerousPermissionCount = 0)
        )
    }

    @Test
    fun `любой из признаков делает приложение заметным`() {
        assertTrue(HiddenAppRiskPolicy.isReportable(true, 0))
        assertTrue(HiddenAppRiskPolicy.isReportable(false, 1))
        assertTrue(HiddenAppRiskPolicy.isReportable(true, 5))
    }
}
