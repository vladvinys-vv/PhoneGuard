package com.phoneguard.simswap

import com.phoneguard.model.SimSwapEvent

/**
 * Правила «какое событие подмены SIM ещё ждёт ответа пользователя».
 *
 * Логика была размазана по `SimSwapRepository.latestUnconfirmed()` (один `takeIf`) и
 * `SimSwapDetector`, который дёргает её из корутины с `withContext(IO)`. Проверить это
 * в JVM было нельзя — репозиторий требует `AppDatabase`. Здесь только чистые правила.
 *
 * Важно, чего правило **не** делает: оно не смотрит на `isAttackSuspected`. Метка атаки
 * ставится при отказе пользователя («я SIM не менял») вместе с подтверждением, поэтому
 * «помечено атакой и всё ещё не подтверждено» — это состояние незавершённого отказа,
 * а не повод спрашивать заново.
 */
internal object SimSwapEventPolicy {

    /** Событие ждёт ответа: пользователь ещё не сказал, он ли менял SIM. */
    fun isPending(event: SimSwapEvent): Boolean = !event.isConfirmed

    /** Последнее событие, которое ещё ждёт ответа, или `null`, если ждать нечего. */
    fun pending(latest: SimSwapEvent?): SimSwapEvent? = latest?.takeIf(::isPending)
}
