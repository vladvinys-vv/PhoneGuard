package com.phoneguard.simswap

import com.phoneguard.model.SimSwapEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Какое событие подмены SIM ещё ждёт ответа пользователя.
 *
 * Правило было в `SimSwapRepository.latestUnconfirmed()` — то есть за `AppDatabase` и корутиной
 * с `withContext(IO)`, и не исполнялось в тестах ни разу. От него зависит, покажет ли приложение
 * вопрос «вы меняли SIM?» после перезапуска.
 */
class SimSwapEventPolicyTest {

    private fun event(
        id: Long = 1,
        isConfirmed: Boolean = false,
        isAttackSuspected: Boolean = false
    ) = SimSwapEvent(
        id = id,
        timestamp = 1_700_000_000_000,
        oldImsi = "old-imsi",
        newImsi = "new-imsi",
        latitude = null,
        longitude = null,
        addressString = null,
        isConfirmed = isConfirmed,
        isAttackSuspected = isAttackSuspected
    )

    @Test
    fun unconfirmedEventIsPending() {
        val pending = event()
        assertTrue(SimSwapEventPolicy.isPending(pending))
        assertSame(pending, SimSwapEventPolicy.pending(pending))
    }

    @Test
    fun confirmedEventIsNotPending() {
        val confirmed = event(isConfirmed = true)
        assertFalse(SimSwapEventPolicy.isPending(confirmed))
        assertNull("подтверждённое событие не должно спрашиваться снова", SimSwapEventPolicy.pending(confirmed))
    }

    @Test
    fun noEventsMeansNothingToAsk() {
        assertNull(SimSwapEventPolicy.pending(null))
    }

    @Test
    fun attackFlagAloneDoesNotHideTheQuestion() {
        // `isAttackSuspected` ставится при отказе пользователя, но подтверждение — отдельный
        // флаг. Если когда-нибудь порядок изменится, вопрос всё равно должен остаться:
        // метка атаки не заменяет ответ пользователя.
        assertTrue(SimSwapEventPolicy.isPending(event(isAttackSuspected = true)))
    }

    @Test
    fun confirmedAttackIsNotPending() {
        // Текущая цепочка отказа: подтвердить + пометить атакой. Спрашивать больше нечего.
        assertNull(SimSwapEventPolicy.pending(event(isConfirmed = true, isAttackSuspected = true)))
    }

    @Test
    fun onlyTheLatestEventMatters() {
        // Правило принимает ровно одно («последнее») событие: история не должна возвращать
        // пользователя к старым вопросам после того, как он ответил на новый.
        val latest = event(id = 5)
        assertEquals(5L, SimSwapEventPolicy.pending(latest)?.id)
        assertNull(SimSwapEventPolicy.pending(event(id = 5, isConfirmed = true)))
    }
}
