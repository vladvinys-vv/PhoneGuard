package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Антидребезг активных реакций на вторжение.
 *
 * Повод: серия одинаковых событий давала серию одинаковых реакции — пять правил фаервола на
 * один пакет, пять SMS доверенным контактам, пять запусков скрытой съёмки. У приманок
 * антидребезг был, у остальных действий — нет.
 */
class IntrusionReactionGateTest {

    private val event = IntrusionEvent(
        type = IntrusionType.BRUTE_FORCE,
        severity = IntrusionSeverity.HIGH,
        packageName = "com.evil"
    )

    private fun gate(windowMs: Long = 30_000L, maxEntries: Int = 64) =
        IntrusionReactionGate(windowMs = windowMs, maxEntries = maxEntries)

    private fun key(action: IntrusionResponseAction, from: IntrusionEvent = event) =
        IntrusionReactionGate.keyFor(from, action)

    @Test
    fun firstReactionRuns() {
        val gate = gate()
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 1_000L))
    }

    @Test
    fun repeatedReactionWithinWindowIsSuppressed() {
        val gate = gate()
        val alarm = key(IntrusionResponseAction.StartAlarm)
        assertTrue(gate.shouldRun(alarm, 1_000L))
        assertFalse(gate.shouldRun(alarm, 2_000L))
        assertFalse(gate.shouldRun(alarm, 30_999L))
    }

    @Test
    fun reactionRunsAgainAfterWindow() {
        val gate = gate()
        val alarm = key(IntrusionResponseAction.StartAlarm)
        assertTrue(gate.shouldRun(alarm, 1_000L))
        // Ровно на границе окна повтор снова разрешён.
        assertTrue(gate.shouldRun(alarm, 31_000L))
        assertFalse(gate.shouldRun(alarm, 31_500L))
    }

    @Test
    fun differentActionsOfSameEventAreIndependent() {
        val gate = gate()
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 1_000L))
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.CapturePhoto), 1_000L))
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.NotifyTrustedContacts(1)), 1_000L))
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.BlockPackage("com.evil")), 1_000L))
    }

    @Test
    fun differentEventTypesAreIndependent() {
        val gate = gate()
        val rat = event.copy(type = IntrusionType.RAT_DETECTED)
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 1_000L))
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm, rat), 1_000L))
    }

    @Test
    fun differentPackagesAreIndependent() {
        val gate = gate()
        val other = event.copy(packageName = "com.other")
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 1_000L))
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm, other), 1_000L))
        assertFalse(gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 2_000L))
    }

    @Test
    fun eventWithoutPackageHasItsOwnKey() {
        val gate = gate()
        val noPackage = event.copy(packageName = null)
        assertEquals(
            "BRUTE_FORCE|-|START_ALARM",
            key(IntrusionResponseAction.StartAlarm, noPackage)
        )
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm, noPackage), 1_000L))
        // Пакетное событие того же типа подавляться не должно.
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 1_000L))
    }

    @Test
    fun informationalActionsAreNeverGated() {
        assertFalse(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.WARNING))
        assertFalse(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.LOG_EXPORT))
        // Сейф запирается идемпотентно, дребезг ему не нужен.
        assertFalse(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.LOCK_VAULT))
        // Приманки уже имеют собственный антидребезг по путям.
        assertFalse(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.HONEYPOT))
    }

    @Test
    fun activeActionsAreGated() {
        assertTrue(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.ALARM))
        assertTrue(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.PHOTO))
        assertTrue(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.NOTIFY_CONTACTS))
        assertTrue(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.BLOCK_PACKAGE))
        assertTrue(IntrusionReactionGate.shouldGate(IntrusionResponsePolicy.Category.BLOCK_NETWORK))
    }

    @Test
    fun actionNamesAreStableAndDistinct() {
        val actions = listOf(
            IntrusionResponseAction.ShowWarning("t", 1),
            IntrusionResponseAction.ExportLog,
            IntrusionResponseAction.LockVault,
            IntrusionResponseAction.BlockPackage("a"),
            IntrusionResponseAction.BlockNetwork(null),
            IntrusionResponseAction.StartAlarm,
            IntrusionResponseAction.CapturePhoto,
            IntrusionResponseAction.NotifyTrustedContacts(1),
            IntrusionResponseAction.CreateHoneypot("h")
        )
        val names = actions.map { IntrusionReactionGate.actionName(it) }
        assertEquals(names.size, names.toSet().size)
        // Имя не зависит от данных действия: ключ не должен меняться от текста предупреждения.
        assertEquals(
            IntrusionReactionGate.actionName(IntrusionResponseAction.BlockPackage("a")),
            IntrusionReactionGate.actionName(IntrusionResponseAction.BlockPackage("b"))
        )
        assertNotEquals(
            IntrusionReactionGate.actionName(IntrusionResponseAction.StartAlarm),
            IntrusionReactionGate.actionName(IntrusionResponseAction.CapturePhoto)
        )
    }

    @Test
    fun clockGoingBackwardsKeepsSuppressionInsteadOfLooping() {
        // Время монотонное; при откате назад окно не «переоткрывается» бесконечно.
        val gate = gate()
        val alarm = key(IntrusionResponseAction.StartAlarm)
        assertTrue(gate.shouldRun(alarm, 60_000L))
        assertFalse(gate.shouldRun(alarm, 10_000L))
    }

    @Test
    fun trackedCountAndReset() {
        val gate = gate()
        assertEquals(0, gate.trackedCount())
        gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 1_000L)
        gate.shouldRun(key(IntrusionResponseAction.CapturePhoto), 1_000L)
        assertEquals(2, gate.trackedCount())
        gate.reset()
        assertEquals(0, gate.trackedCount())
        assertTrue(gate.shouldRun(key(IntrusionResponseAction.StartAlarm), 1_000L))
    }

    @Test
    fun overflowDoesNotDropNewReaction() {
        val gate = gate(windowMs = 100L, maxEntries = 2)
        assertTrue(gate.shouldRun("a", 1_000L))
        assertTrue(gate.shouldRun("b", 1_000L))
        // Карта переполнена: старые записи в окне, поэтому сбрасываем целиком.
        assertTrue(gate.shouldRun("c", 1_000L))
        assertTrue(gate.trackedCount() <= 2)
    }
}
