package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жизненный цикл наблюдателя за приманками.
 *
 * Ошибка, которую эти тесты закрывают, была невидимой: `HoneypotObserver.stop()` существовал,
 * но не вызывался, поэтому выключенная защита продолжала писать события. Здесь закреплено, что
 * состояние наблюдателя следует за настройкой в обе стороны и что решение сходится (после
 * применения действия повторный вызов ничего не меняет).
 */
class HoneypotLifecyclePolicyTest {

    @Test
    fun enabledWithoutObserverStarts() {
        assertEquals(
            HoneypotLifecycleAction.START,
            HoneypotLifecyclePolicy.decide(enabled = true, observerRunning = false)
        )
    }

    @Test
    fun enabledWithObserverDoesNothing() {
        assertEquals(
            HoneypotLifecycleAction.NONE,
            HoneypotLifecyclePolicy.decide(enabled = true, observerRunning = true)
        )
    }

    @Test
    fun disabledWithObserverStops() {
        assertEquals(
            HoneypotLifecycleAction.STOP,
            HoneypotLifecyclePolicy.decide(enabled = false, observerRunning = true)
        )
    }

    @Test
    fun disabledWithoutObserverDoesNothing() {
        assertEquals(
            HoneypotLifecycleAction.NONE,
            HoneypotLifecyclePolicy.decide(enabled = false, observerRunning = false)
        )
    }

    @Test
    fun decisionConvergesAfterApplyingIt() {
        // START → наблюдатель работает → повторное решение NONE. То же для STOP.
        val start = HoneypotLifecyclePolicy.decide(enabled = true, observerRunning = false)
        assertEquals(HoneypotLifecycleAction.START, start)
        assertEquals(
            HoneypotLifecycleAction.NONE,
            HoneypotLifecyclePolicy.decide(enabled = true, observerRunning = true)
        )

        val stop = HoneypotLifecyclePolicy.decide(enabled = false, observerRunning = true)
        assertEquals(HoneypotLifecycleAction.STOP, stop)
        assertEquals(
            HoneypotLifecycleAction.NONE,
            HoneypotLifecyclePolicy.decide(enabled = false, observerRunning = false)
        )
    }

    @Test
    fun fullCycleEnableDisableEnable() {
        // Пользователь выключил и снова включил защиту: она обязана вернуться в работу,
        // а не остаться «мёртвой» из-за остановленного наблюдателя.
        val states = listOf(
            HoneypotLifecyclePolicy.decide(enabled = true, observerRunning = false),
            HoneypotLifecyclePolicy.decide(enabled = true, observerRunning = true),
            HoneypotLifecyclePolicy.decide(enabled = false, observerRunning = true),
            HoneypotLifecyclePolicy.decide(enabled = false, observerRunning = false),
            HoneypotLifecyclePolicy.decide(enabled = true, observerRunning = false)
        )
        assertEquals(
            listOf(
                HoneypotLifecycleAction.START,
                HoneypotLifecycleAction.NONE,
                HoneypotLifecycleAction.STOP,
                HoneypotLifecycleAction.NONE,
                HoneypotLifecycleAction.START
            ),
            states
        )
    }

    @Test
    fun accessIsReportedOnlyWhenEnabledAndDecoy() {
        assertTrue(HoneypotLifecyclePolicy.shouldReportAccess(enabled = true, isDecoy = true))
    }

    @Test
    fun accessToOrdinaryFileIsNotReported() {
        assertFalse(HoneypotLifecyclePolicy.shouldReportAccess(enabled = true, isDecoy = false))
    }

    @Test
    fun disabledProtectionReportsNothing() {
        // Событие из очереди inotify может прийти уже после выключения защиты.
        assertFalse(HoneypotLifecyclePolicy.shouldReportAccess(enabled = false, isDecoy = true))
        assertFalse(HoneypotLifecyclePolicy.shouldReportAccess(enabled = false, isDecoy = false))
    }
}
