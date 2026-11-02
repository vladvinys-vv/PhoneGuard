package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила подписей: что считается находкой и насколько серьёзной.
 *
 * Тесты выполняются на JVM — в отличие от прежней логики внутри `performCheck()`,
 * которую без устройства (Robolectric) проверить было нельзя.
 */
class ApkSignaturePolicyTest {

    private fun severities(state: ApkSignaturePolicy.State) =
        ApkSignaturePolicy.assess(state).map { it.severity }

    private fun reasons(state: ApkSignaturePolicy.State) =
        ApkSignaturePolicy.assess(state).map { it.reason }

    @Test
    fun `чистая подпись не даёт находок`() {
        assertTrue(ApkSignaturePolicy.assess(ApkSignaturePolicy.State()).isEmpty())
    }

    @Test
    fun `debug-ключ из магазина — критично`() {
        val state = ApkSignaturePolicy.State(
            installedFromDebugKey = true,
            installedFromStore = true
        )
        assertEquals(
            listOf(ApkSignaturePolicy.Verdict(
                ApkSignaturePolicy.Reason.DEBUG_KEY_FROM_STORE,
                ApkSignaturePolicy.Severity.CRITICAL
            )),
            ApkSignaturePolicy.assess(state)
        )
    }

    @Test
    fun `debug-ключ у sideload-приложения — только предупреждение`() {
        val state = ApkSignaturePolicy.State(installedFromDebugKey = true)
        assertEquals(
            listOf(ApkSignaturePolicy.Reason.DEBUG_KEY),
            reasons(state)
        )
        assertEquals(listOf(ApkSignaturePolicy.Severity.WARNING), severities(state))
    }

    @Test
    fun `собственная debuggable-сборка с debug-ключом не считается находкой`() {
        val state = ApkSignaturePolicy.State(
            installedFromDebugKey = true,
            debuggable = true,
            installedFromStore = false
        )
        assertTrue(ApkSignaturePolicy.assess(state).isEmpty())
    }

    @Test
    fun `слабый алгоритм — предупреждение`() {
        val state = ApkSignaturePolicy.State(weakSignature = true)
        assertEquals(listOf(ApkSignaturePolicy.Reason.WEAK_ALGORITHM), reasons(state))
        assertEquals(listOf(ApkSignaturePolicy.Severity.WARNING), severities(state))
    }

    @Test
    fun `истёкший сертификат — информационно`() {
        val state = ApkSignaturePolicy.State(certificateExpired = true)
        assertEquals(listOf(ApkSignaturePolicy.Reason.EXPIRED_CERTIFICATE), reasons(state))
        assertEquals(listOf(ApkSignaturePolicy.Severity.INFO), severities(state))
    }

    @Test
    fun `изменённый после установки APK — предупреждение`() {
        val state = ApkSignaturePolicy.State(modifiedAfterInstall = true)
        assertEquals(listOf(ApkSignaturePolicy.Reason.MODIFIED_AFTER_INSTALL), reasons(state))
        assertEquals(listOf(ApkSignaturePolicy.Severity.WARNING), severities(state))
    }

    @Test
    fun `все признаки сразу — критичное первым`() {
        val state = ApkSignaturePolicy.State(
            installedFromDebugKey = true,
            installedFromStore = true,
            weakSignature = true,
            certificateExpired = true,
            modifiedAfterInstall = true
        )
        val verdicts = ApkSignaturePolicy.assess(state)
        assertEquals(4, verdicts.size)
        assertEquals(ApkSignaturePolicy.Severity.CRITICAL, verdicts.first().severity)
        assertEquals(
            listOf(
                ApkSignaturePolicy.Reason.DEBUG_KEY_FROM_STORE,
                ApkSignaturePolicy.Reason.WEAK_ALGORITHM,
                ApkSignaturePolicy.Reason.EXPIRED_CERTIFICATE,
                ApkSignaturePolicy.Reason.MODIFIED_AFTER_INSTALL
            ),
            verdicts.map { it.reason }
        )
    }

    @Test
    fun `debuggable не скрывает остальные находки`() {
        val state = ApkSignaturePolicy.State(
            installedFromDebugKey = true,
            debuggable = true,
            weakSignature = true
        )
        assertEquals(listOf(ApkSignaturePolicy.Reason.WEAK_ALGORITHM), reasons(state))
    }

    @Test
    fun `магазины распознаются, неизвестный установщик — нет`() {
        assertTrue(ApkSignaturePolicy.isStoreInstaller("com.android.vending"))
        assertTrue(ApkSignaturePolicy.isStoreInstaller("com.huawei.appmarket"))
        assertFalse(ApkSignaturePolicy.isStoreInstaller("com.example.filemanager"))
        // Сторонний установщик не должен давать CRITICAL: пакет мог быть скачан вручную.
        assertFalse(ApkSignaturePolicy.isStoreInstaller(null))
    }

    @Test
    fun `бюджет проверки считается по монотонным часам`() {
        assertTrue(ApkSignaturePolicy.withinBudget(nowElapsedMs = 1_000, deadlineElapsedMs = 1_000))
        assertTrue(ApkSignaturePolicy.withinBudget(nowElapsedMs = 999, deadlineElapsedMs = 1_000))
        assertFalse(ApkSignaturePolicy.withinBudget(nowElapsedMs = 1_001, deadlineElapsedMs = 1_000))
    }
}
