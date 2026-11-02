package com.phoneguard.simswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подтверждение и опровержение подмены SIM.
 *
 * Главное различие, которое легко потерять при правках: отказ пользователя («я SIM не
 * менял») включает lost mode и метку «подозрение на атаку», а подтверждение — нет.
 */
class SimSwapConfirmPolicyTest {

    @Test
    fun `подтверждение при наличии события`() {
        val plan = SimSwapConfirmPolicy.plan(SimSwapConfirmPolicy.Choice.CONFIRM, hasPendingEvent = true)
        assertTrue(plan.confirmLatestEvent)
        assertFalse(plan.markAttackSuspected)
        assertTrue(plan.clearUnconfirmedFlag)
        assertTrue(plan.cancelNotification)
        assertFalse(plan.activateLostMode)
    }

    @Test
    fun `отказ при наличии события — lost mode и метка атаки`() {
        val plan = SimSwapConfirmPolicy.plan(SimSwapConfirmPolicy.Choice.REJECT, hasPendingEvent = true)
        assertTrue(plan.confirmLatestEvent)
        assertTrue(plan.markAttackSuspected)
        assertTrue(plan.clearUnconfirmedFlag)
        assertTrue(plan.cancelNotification)
        assertTrue(plan.activateLostMode)
    }

    @Test
    fun `без события флаги всё равно снимаются`() {
        // Кнопка в настройках при отсутствии незакрытого события: баннер не должен
        // оставаться на экране.
        listOf(SimSwapConfirmPolicy.Choice.CONFIRM, SimSwapConfirmPolicy.Choice.REJECT).forEach { choice ->
            val plan = SimSwapConfirmPolicy.plan(choice, hasPendingEvent = false)
            assertFalse(choice.name, plan.confirmLatestEvent)
            assertFalse(choice.name, plan.markAttackSuspected)
            assertTrue(choice.name, plan.clearUnconfirmedFlag)
            assertTrue(choice.name, plan.cancelNotification)
        }
    }

    @Test
    fun `отказ без события всё равно включает lost mode`() {
        val plan = SimSwapConfirmPolicy.plan(SimSwapConfirmPolicy.Choice.REJECT, hasPendingEvent = false)
        assertTrue(plan.activateLostMode)
    }

    @Test
    fun `подтверждение никогда не включает lost mode`() {
        listOf(true, false).forEach { pending ->
            assertFalse(SimSwapConfirmPolicy.plan(SimSwapConfirmPolicy.Choice.CONFIRM, pending).activateLostMode)
        }
    }

    @Test
    fun `метка атаки ставится только при отказе`() {
        assertEquals(
            false,
            SimSwapConfirmPolicy.plan(SimSwapConfirmPolicy.Choice.CONFIRM, hasPendingEvent = true).markAttackSuspected
        )
        assertEquals(
            true,
            SimSwapConfirmPolicy.plan(SimSwapConfirmPolicy.Choice.REJECT, hasPendingEvent = true).markAttackSuspected
        )
    }
}
