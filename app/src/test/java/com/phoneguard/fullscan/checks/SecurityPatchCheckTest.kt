package com.phoneguard.fullscan.checks

import com.phoneguard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Проверка уровня security-патча: пороги и отказоустойчивость разбора даты.
 *
 * `Build.VERSION.SECURITY_PATCH` в JVM-тестах не задан, поэтому проверяется
 * чистая функция [SecurityPatchCheck.evaluate].
 *
 * Диагностика теперь не строки, а [CheckDetail] (ресурс + аргументы), поэтому
 * тесты сверяют `textRes`, а не русские слова: правка формулировки или перевод
 * больше не ломает тест, а отсутствие перевода видно как другой ресурс.
 */
class SecurityPatchCheckTest {

    private val check = SecurityPatchCheck()

    private fun dateOf(isoDate: String): Long =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)!!.time

    private val now = dateOf("2025-06-15")

    private fun SecurityPatchCheck.PatchResult.has(textRes: Int): Boolean =
        details.any { it.textRes == textRes }

    @Test
    fun freshPatchIsNotCritical() {
        val result = check.evaluate("2025-06-01", now)
        assertFalse(result.isCritical)
        assertEquals(0, result.monthsOutdated)
        assertTrue(result.has(R.string.patch_detail_fresh))
    }

    @Test
    fun fourMonthsOldPatchIsWarningButNotCritical() {
        val result = check.evaluate("2025-02-01", now)
        assertEquals(4, result.monthsOutdated)
        assertFalse(result.isCritical)
        assertTrue(result.has(R.string.patch_detail_warning))
        // Предупреждение содержит именно порог, а не «магическую» константу.
        assertEquals(
            listOf(3),
            result.details.first { it.textRes == R.string.patch_detail_warning }.args
        )
    }

    @Test
    fun yearOldPatchIsCritical() {
        val result = check.evaluate("2024-05-01", now)
        assertTrue(result.monthsOutdated >= 12)
        assertTrue(result.isCritical)
        assertTrue(result.has(R.string.patch_detail_critical))
    }

    @Test
    fun blankPatchDateIsTreatedAsUnknown() {
        val result = check.evaluate("", now)
        assertTrue(result.isCritical)
        // Пустая строка вместо русского «неизвестно»: подпись подставляет
        // потребитель результата (`R.string.patch_date_unknown`).
        assertEquals("", result.patchDate)
        assertTrue(result.has(R.string.patch_detail_unknown_date))
    }

    @Test
    fun malformedDatesAreRejected() {
        listOf("не дата", "2024-13-45", "2024-01-01-хвост", "2024-1").forEach { value ->
            val result = check.evaluate(value, now)
            assertTrue("должна быть критичной: $value", result.isCritical)
            assertTrue(
                "ожидалась строка про нераспознанный формат: $value",
                result.details.any {
                    it.textRes == R.string.patch_detail_unparsable && it.args == listOf(value)
                }
            )
        }
    }

    @Test
    fun futurePatchDateDoesNotLookFresh() {
        // Сбитые часы/OEM-баг: дата «из будущего» — отрицательных месяцев быть не должно.
        val result = check.evaluate("2026-01-01", now)
        assertEquals(0, result.monthsOutdated)
        assertTrue(result.has(R.string.patch_detail_future))
    }
}
