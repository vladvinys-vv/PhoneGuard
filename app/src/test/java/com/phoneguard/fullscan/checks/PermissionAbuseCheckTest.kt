package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionAbuseCheckTest {

    private val context = mockk<Context>()
    private val pm = mockk<PackageManager>()
    private val check = PermissionAbuseCheck(context)

    @Test
    fun `assessRisk returns CRITICAL for camera microphone location combo`() {
        val groups = setOf("Камера", "Микрофон", "Местоположение")
        val result = check.assessRisk(groups)
        assertEquals(PermissionAbuseCheck.RiskLevel.CRITICAL, result.first)
    }

    @Test
    fun `assessRisk returns HIGH for SMS plus other permissions`() {
        val groups = setOf("СМС", "Контакты")
        val result = check.assessRisk(groups)
        assertEquals(PermissionAbuseCheck.RiskLevel.HIGH, result.first)
    }

    @Test
    fun `assessRisk returns null for single safe permission`() {
        val groups = setOf("Уведомления")
        val result = check.assessRisk(groups)
        assertNull(result.first)
    }

    @Test
    fun `performCheck filters system apps`() {
        every { context.packageManager } returns pm
        every { pm.getInstalledApplications(any()) } returns listOf(
            createAppInfo("com.system.app", isSystem = true),
            createAppInfo("com.user.app", isSystem = false)
        )
        every { pm.getApplicationLabel(any()) } returns "Test"
        every { pm.getPackageInfo("com.user.app", any()) } returns PackageInfo().apply {
            requestedPermissions = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        }

        val result = check.performCheck()
        assertTrue(result.riskyApps.none { it.packageName == "com.system.app" })
    }

    private fun createAppInfo(packageName: String, isSystem: Boolean): android.content.pm.ApplicationInfo {
        return android.content.pm.ApplicationInfo().apply {
            this.packageName = packageName
            flags = if (isSystem) android.content.pm.ApplicationInfo.FLAG_SYSTEM else 0
        }
    }
}
