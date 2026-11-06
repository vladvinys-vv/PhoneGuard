package com.phoneguard.perm

import com.phoneguard.perm.ConsentPolicy.State
import com.phoneguard.perm.ProtectionActionPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Состояние разрешения на экране согласия.
 *
 * Цена ошибки здесь не в тексте, а в тупике: если «отказано навсегда» считается тем же, что
 * «ещё не спрашивали», кнопка «Принять и продолжить» остаётся выключенной навсегда, а нажатие на
 * галочку ничего не делает — Android второй диалог не покажет.
 */
class ConsentPolicyTest {

    @Test
    fun `granted permission needs no action`() {
        assertEquals(State.GRANTED, ConsentPolicy.state(true, askedBefore = true, canAskAgain = false))
        assertNull(ConsentPolicy.action(State.GRANTED))
    }

    @Test
    fun `never asked permission is asked`() {
        assertEquals(State.NOT_ASKED, ConsentPolicy.state(false, askedBefore = false, canAskAgain = false))
        assertEquals(Action.REQUEST_PERMISSION, ConsentPolicy.action(State.NOT_ASKED))
    }

    @Test
    fun `denied once but askable is asked again`() {
        assertEquals(
            State.DECLINED_CAN_RETRY,
            ConsentPolicy.state(false, askedBefore = true, canAskAgain = true)
        )
        assertEquals(Action.REQUEST_PERMISSION, ConsentPolicy.action(State.DECLINED_CAN_RETRY))
    }

    @Test
    fun `denied forever opens app settings instead of a dialog`() {
        // Ключевой случай: Android больше не покажет диалог, и «Разрешить» молча ничего не сделает.
        assertEquals(
            State.DECLINED_FOREVER,
            ConsentPolicy.state(false, askedBefore = true, canAskAgain = false)
        )
        assertEquals(Action.OPEN_APP_DETAILS, ConsentPolicy.action(State.DECLINED_FOREVER))
    }

    @Test
    fun `only never asked counts as unanswered`() {
        assertFalse(ConsentPolicy.isAnswered(State.NOT_ASKED))
        assertTrue(ConsentPolicy.isAnswered(State.GRANTED))
        assertTrue(ConsentPolicy.isAnswered(State.DECLINED_CAN_RETRY))
        assertTrue(ConsentPolicy.isAnswered(State.DECLINED_FOREVER))
    }

    @Test
    fun `onboarding cannot be finished while something is unanswered`() {
        assertFalse(ConsentPolicy.canContinue(listOf(State.GRANTED, State.NOT_ASKED)))
        assertEquals(1, ConsentPolicy.pendingCount(listOf(State.GRANTED, State.NOT_ASKED)))
    }

    @Test
    fun `permanent denial does not block onboarding`() {
        // Раньше требовались все разрешения, и человек с «больше не спрашивать» застревал на экране.
        assertTrue(ConsentPolicy.canContinue(listOf(State.GRANTED, State.DECLINED_FOREVER)))
        assertTrue(ConsentPolicy.canContinue(listOf(State.DECLINED_CAN_RETRY)))
    }

    @Test
    fun `all granted is the happy path`() {
        assertTrue(ConsentPolicy.canContinue(listOf(State.GRANTED, State.GRANTED)))
        assertEquals(0, ConsentPolicy.pendingCount(listOf(State.GRANTED, State.GRANTED)))
        assertEquals(0, ConsentPolicy.disabledCount(listOf(State.GRANTED, State.GRANTED)))
    }

    @Test
    fun `declined features are counted for the hint`() {
        assertEquals(
            2,
            ConsentPolicy.disabledCount(
                listOf(State.GRANTED, State.DECLINED_CAN_RETRY, State.DECLINED_FOREVER, State.NOT_ASKED)
            )
        )
    }

    @Test
    fun `empty list is not a blocker`() {
        assertTrue(ConsentPolicy.canContinue(emptyList()))
        assertEquals(0, ConsentPolicy.pendingCount(emptyList()))
    }
}
