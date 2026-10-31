package com.phoneguard.fullscan.checks

import android.content.Context
import com.phoneguard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Root-детект: эвристики и формат диагностики.
 *
 * Раньше здесь был тест `assessRisk(...)`, который вообще не относился к этому
 * классу (такой метод есть только у `PermissionAbuseCheck`) — файл не
 * компилировался. Логика оценки риска разрешений проверяется в
 * `PermissionAbuseCheckTest`, а здесь остаются честные root-эвристики.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RootDetectionCheckTest {

    // `RuntimeEnvironment` вместо `ApplicationProvider`: последний живёт в
    // `androidx.test:core`, которого нет среди testImplementation-зависимостей.
    private val context: Context = RuntimeEnvironment.getApplication()
    private val check = RootDetectionCheck(context)

    @Test
    fun `diagnostics come from string resources`() {
        val result = check.performCheck()

        assertTrue("диагностика не должна быть пустой", result.details.isNotEmpty())
        // В тестовой среде нет ни su-binary, ни root-приложений.
        assertTrue(
            "ожидалась локализованная строка об отсутствии su: ${result.details}",
            result.details.contains(context.getString(R.string.root_detail_su_not_found))
        )
        assertTrue(
            "ожидалась локализованная строка об отсутствии root-приложений: ${result.details}",
            result.details.contains(context.getString(R.string.root_detail_apps_none))
        )
    }

    @Test
    fun `confidence stays within 0 and 1`() {
        val result = check.performCheck()
        assertTrue(result.confidence in 0f..1f)
        // Одна строка диагностики на проверку — уверенность = индикаторы/проверки.
        assertEquals(result.details.size, result.details.distinct().size)
    }

    @Test
    fun `plain test environment is not reported as rooted`() {
        // Порог срабатывания — 3 независимых индикатора, здесь их не набирается.
        assertFalse(check.performCheck().isRooted)
    }
}
