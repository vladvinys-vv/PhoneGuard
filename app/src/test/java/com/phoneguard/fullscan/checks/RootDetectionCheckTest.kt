package com.phoneguard.fullscan.checks

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RootDetectionCheckTest {

    private val context = mockk<Context>()
    private val check = RootDetectionCheck(context)

    @Test
    fun `performCheck returns not rooted when no indicators`() {
        every { context.packageManager } returns mockk()
        every { android.os.Build.TAGS } returns "release-keys"

        val result = check.performCheck()
        assertFalse(result.isRooted)
    }

    @Test
    fun `assessRisk returns CRITICAL for critical combination`() {
        val groups = setOf("Камера", "Микрофон", "Местоположение")
        val result = check.assessRisk(groups)
        assertEquals(PermissionAbuseCheck.RiskLevel.CRITICAL, result.first)
    }
}
