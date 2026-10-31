package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Комбинации разрешений: критичность и фильтрация системных приложений.
 *
 * `assessRisk` больше не требует `Context` (он возвращает `CheckDetail` —
 * ресурс + аргументы), поэтому логику можно проверять без mock-контекста,
 * которому пришлось бы «объяснять» каждый `getString` с vararg.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionAbuseCheckTest {

    private val groups = PermissionAbuseCheck.PermissionGroup

    /** Заглушка локализации: тест проверяет риск, а не перевод. */
    private val labels: (Collection<PermissionAbuseCheck.PermissionGroup>) -> String = { list ->
        list.joinToString(",") { it.name }
    }

    private fun context(): Context = mockk(relaxed = true)

    @Test
    fun `assessRisk returns CRITICAL for camera microphone location combo`() {
        val check = PermissionAbuseCheck(context())
        val result = check.assessRisk(
            setOf(groups.CAMERA, groups.MICROPHONE, groups.LOCATION),
            labels
        )
        assertEquals(PermissionAbuseCheck.RiskLevel.CRITICAL, result.first)
        assertNotNull(result.second)
    }

    @Test
    fun `assessRisk returns HIGH for SMS plus other permissions`() {
        val check = PermissionAbuseCheck(context())
        val result = check.assessRisk(setOf(groups.SMS, groups.PHONE), labels)
        assertEquals(PermissionAbuseCheck.RiskLevel.HIGH, result.first)
    }

    @Test
    fun `assessRisk returns MEDIUM for location alone`() {
        val check = PermissionAbuseCheck(context())
        val result = check.assessRisk(setOf(groups.LOCATION), labels)
        assertEquals(PermissionAbuseCheck.RiskLevel.MEDIUM, result.first)
    }

    @Test
    fun `assessRisk returns null for a single harmless permission`() {
        val check = PermissionAbuseCheck(context())
        // Уведомления намеренно не входят в «опасные» группы: раньше они давали
        // ложные срабатывания, поэтому такой группы в enum просто нет.
        val result = check.assessRisk(setOf(groups.CONTACTS), labels)
        assertNull(result.first)
    }

    @Test
    fun `performCheck ignores system apps and flags granted dangerous combo`() {
        val context = context()
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm
        every { pm.getApplicationLabel(any()) } returns "Test"
        every { pm.checkPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val systemApp = ApplicationInfo().apply {
            packageName = "com.system.app"
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        val userApp = ApplicationInfo().apply {
            packageName = "com.user.app"
            flags = 0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            every {
                pm.getInstalledApplications(any<PackageManager.ApplicationInfoFlags>())
            } returns listOf(systemApp, userApp)
            every {
                pm.getPackageInfo("com.user.app", any<PackageManager.PackageInfoFlags>())
            } returns PackageInfo().apply {
                requestedPermissions = arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
            every {
                pm.getPackageInfo("com.system.app", any<PackageManager.PackageInfoFlags>())
            } returns PackageInfo()
        } else {
            @Suppress("DEPRECATION")
            every { pm.getInstalledApplications(any<Int>()) } returns listOf(systemApp, userApp)
            @Suppress("DEPRECATION")
            every {
                pm.getPackageInfo("com.user.app", any<Int>())
            } returns PackageInfo().apply {
                requestedPermissions = arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
            @Suppress("DEPRECATION")
            every { pm.getPackageInfo("com.system.app", any<Int>()) } returns PackageInfo()
        }

        // Реальный `Context` не нужен: локализация идёт только для найденных
        // рисков, а mockk-контекст отдаёт пустые строки.
        val check = PermissionAbuseCheck(context)
        val result = check.performCheck()

        assertTrue(
            "системное приложение не должно попадать в отчёт",
            result.riskyApps.none { it.packageName == "com.system.app" }
        )
        assertEquals(1, result.criticalCount)
        assertEquals("com.user.app", result.riskyApps.single().packageName)
    }
}
