package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок проверок Device Admin: свои права → известные легитимные → системные →
 * удалённое приложение → подозрительное имя → сторонний администратор.
 */
class DeviceAdminRiskPolicyTest {

    private val own = "com.phoneguard"

    private fun assess(
        pkg: String,
        appName: String? = "Some App",
        system: Boolean = false
    ) = DeviceAdminRiskPolicy.assess(
        packageName = pkg,
        appName = appName,
        isSystemApp = system,
        ownPackage = own
    )

    @Test
    fun `свои права администратора не находка`() {
        assertEquals(DeviceAdminRiskPolicy.Verdict.SKIP_OWN_PACKAGE, assess(own))
        assertFalse(DeviceAdminRiskPolicy.isReportable(assess(own)))
    }

    @Test
    fun `известный MDM не находка, даже если имя «подозрительное»`() {
        // `com.microsoft.intune` содержит «intune», но он в списке легитимных.
        assertEquals(
            DeviceAdminRiskPolicy.Verdict.SKIP_KNOWN_LEGIT,
            assess("com.microsoft.intune", "Intune Company Portal")
        )
        assertEquals(
            DeviceAdminRiskPolicy.Verdict.SKIP_KNOWN_LEGIT,
            assess("com.samsung.android.knox", "Knox Device Policy")
        )
    }

    @Test
    fun `системный администратор не находка`() {
        assertEquals(
            DeviceAdminRiskPolicy.Verdict.SKIP_SYSTEM_APP,
            assess("com.oem.deviceadmin", "OEM Device Policy", system = true)
        )
    }

    @Test
    fun `удалённое приложение с активным admin-компонентом — находка`() {
        val verdict = assess("com.evil.leftover", appName = null, system = false)
        assertEquals(DeviceAdminRiskPolicy.Verdict.APP_REMOVED, verdict)
        assertTrue(DeviceAdminRiskPolicy.isReportable(verdict))
    }

    @Test
    fun `подозрительное имя распознаётся по пакету`() {
        assertEquals(
            DeviceAdminRiskPolicy.Verdict.SUSPICIOUS_NAME,
            assess("com.example.wifihijack.admin", "WiFi Helper")
        )
        assertEquals(
            DeviceAdminRiskPolicy.Verdict.SUSPICIOUS_NAME,
            assess("com.example.tool", "Device Wipe")
        )
        assertEquals(
            DeviceAdminRiskPolicy.Verdict.SUSPICIOUS_NAME,
            assess("com.spy.camera", "Camera")
        )
    }

    @Test
    fun `обычный сторонний администратор`() {
        val verdict = assess("com.example.mdmclient", "Company App")
        assertEquals(DeviceAdminRiskPolicy.Verdict.THIRD_PARTY, verdict)
        assertTrue(DeviceAdminRiskPolicy.isReportable(verdict))
    }

    @Test
    fun `своё приложение в списке легитимных всё равно пропускается как своё`() {
        // Проверка порядка: сначала «свой пакет», потом «известный легитимный».
        val verdict = DeviceAdminRiskPolicy.assess(
            packageName = "com.google.android.gms",
            appName = "Google Play Services",
            isSystemApp = true,
            ownPackage = "com.google.android.gms"
        )
        assertEquals(DeviceAdminRiskPolicy.Verdict.SKIP_OWN_PACKAGE, verdict)
    }

    @Test
    fun `проверка подозрительного имени не зависит от регистра`() {
        assertTrue(DeviceAdminRiskPolicy.isSuspiciousName("com.example.TOOL", "Device ADMIN"))
        assertTrue(DeviceAdminRiskPolicy.isSuspiciousName("com.example.anti.theft.app", "Helper"))
        assertFalse(DeviceAdminRiskPolicy.isSuspiciousName("com.example.calc", "Calculator"))
        // Бывший пропуск: шаблон `anti.theft` не находил ярлык «ANTI-THEFT» через дефис,
        // и приложение-слежка, назвавшееся «анти-воровством», попадало в отчёт как обычный
        // сторонний администратор. Теперь сравнение идёт по словам (NamePatternMatcher).
        assertTrue(DeviceAdminRiskPolicy.isSuspiciousName("com.example.tool", "ANTI-THEFT"))
        assertTrue(DeviceAdminRiskPolicy.isSuspiciousName("com.example.tool", "Anti Theft"))
        assertTrue(DeviceAdminRiskPolicy.isSuspiciousName("com.example.tool", "AntiTheft"))
        // Обратная сторона: короткие общие слова больше не ищутся подстрокой.
        assertFalse(DeviceAdminRiskPolicy.isSuspiciousName("com.example.badminton", "Badminton"))
        assertFalse(DeviceAdminRiskPolicy.isSuspiciousName("com.wallet.blockchain", "Blockchain"))
        assertFalse(DeviceAdminRiskPolicy.isSuspiciousName("com.example.clocks", "Clocks"))
        // Составные слова, начинающиеся с шаблона, по-прежнему находка.
        assertTrue(DeviceAdminRiskPolicy.isSuspiciousName("com.evil.adminservice", "Helper"))
        assertTrue(DeviceAdminRiskPolicy.isSuspiciousName("com.example.app", "App Lock"))
    }

    @Test
    fun `удалённое приложение приоритетнее подозрительного имени`() {
        // Имя неизвестно, но пакет содержит «admin» — всё равно «приложение удалено».
        assertEquals(
            DeviceAdminRiskPolicy.Verdict.APP_REMOVED,
            assess("com.evil.admin.tool", appName = null)
        )
    }
}
