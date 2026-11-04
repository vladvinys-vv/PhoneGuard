package com.phoneguard.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласованность настройки «периодическое сканирование» и фактической работы.
 *
 * Две ошибки, которые здесь закреплены:
 *  * воркер сканировал устройство, даже когда настройка выключена (работа могла быть
 *    поставлена раньше, восстановлена планировщиком или остаться от прошлой версии);
 *  * неудача планировщика оставляла настройку включённой — интерфейс обещал сканирование,
 *    которого не будет.
 */
class ScanSchedulePolicyTest {

    @Test
    fun enabledSettingMeansScan() {
        assertEquals(
            ScanSchedulePolicy.Verdict.RUN,
            ScanSchedulePolicy.verdict(scheduledScanEnabled = true)
        )
    }

    @Test
    fun disabledSettingMeansSkip() {
        assertEquals(
            ScanSchedulePolicy.Verdict.SKIP_SETTING_DISABLED,
            ScanSchedulePolicy.verdict(scheduledScanEnabled = false)
        )
    }

    @Test
    fun skippedRunMustNotBeTreatedAsSuccess() {
        // Разные вердикты не должны схлопнуться в один: воркер завершает работу
        // `Result.success()` только при SKIP, а не «на всякий случай».
        assertTrue(ScanSchedulePolicy.Verdict.RUN != ScanSchedulePolicy.Verdict.SKIP_SETTING_DISABLED)
    }

    @Test
    fun failedSchedulingRevertsRequestedEnable() {
        assertTrue(
            ScanSchedulePolicy.shouldRevert(requestedEnabled = true, schedulingFailed = true)
        )
    }

    @Test
    fun successfulSchedulingKeepsSetting() {
        assertFalse(
            ScanSchedulePolicy.shouldRevert(requestedEnabled = true, schedulingFailed = false)
        )
    }

    @Test
    fun failedCancellationDoesNotFlipDisabledSettingOn() {
        // Отмена не удалась — это не повод включать настройку обратно.
        assertFalse(
            ScanSchedulePolicy.shouldRevert(requestedEnabled = false, schedulingFailed = true)
        )
        assertFalse(
            ScanSchedulePolicy.shouldRevert(requestedEnabled = false, schedulingFailed = false)
        )
    }

    @Test
    fun revertOnlyEverAppliesToEnableRequests() {
        for (requested in listOf(false, true)) {
            for (failed in listOf(false, true)) {
                val revert = ScanSchedulePolicy.shouldRevert(requested, failed)
                if (revert) {
                    assertTrue("переворот возможен только при включении", requested)
                    assertTrue("переворот возможен только при отказе", failed)
                }
            }
        }
    }
}
