package com.phoneguard.antitheft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Антидубликат SMS-команд.
 *
 * Повторная доставка того же SMS — не редкость (оператор повторяет отправку без подтверждения,
 * часть прошивок отдаёт и `SMS_RECEIVED`, и `SMS_DELIVER`). Без защиты каждая копия выполняет
 * команду заново: вторая тревога, второй кадр с камеры, лишние SMS. При этом настоящая вторая
 * команда (например, повторный `LOCATION` через минуту) обязана работать — поэтому окно короткое.
 */
class SmsCommandDedupeGateTest {

    private val start = 1_000_000L
    private val gate = SmsCommandDedupeGate()

    private val command = "+491701234567|#PG:1234:ALARM"

    @Test
    fun firstCommandIsProcessed() {
        assertTrue(gate.shouldProcess(command, start))
        assertEquals(1, gate.trackedCount())
    }

    @Test
    fun immediateRepeatIsSuppressed() {
        assertTrue(gate.shouldProcess(command, start))
        assertFalse(gate.shouldProcess(command, start + 1))
        assertFalse(gate.shouldProcess(command, start + SmsCommandDedupeGate.DUPLICATE_WINDOW_MS - 1))
    }

    @Test
    fun repeatedCommandAfterWindowIsProcessed() {
        assertTrue(gate.shouldProcess(command, start))
        assertTrue(gate.shouldProcess(command, start + SmsCommandDedupeGate.DUPLICATE_WINDOW_MS))
    }

    @Test
    fun differentCommandsAreIndependent() {
        val alarm = "+491701234567|#PG:1234:ALARM"
        val location = "+491701234567|#PG:1234:LOCATION"
        assertTrue(gate.shouldProcess(alarm, start))
        assertTrue(gate.shouldProcess(location, start))
        assertFalse(gate.shouldProcess(alarm, start + 1))
        assertEquals(2, gate.trackedCount())
    }

    @Test
    fun sameTextFromAnotherSenderIsProcessed() {
        // Отпечаток включает отправителя: два разных человека, отправившие одинаковый текст,
        // не должны глушить друг друга.
        assertTrue(gate.shouldProcess("+491700000001|#PG:1234:STATUS", start))
        assertTrue(gate.shouldProcess("+491700000002|#PG:1234:STATUS", start))
    }

    @Test
    fun clockGoingBackwardsDoesNotOpenTheWindow() {
        // Монотонные часы не должны идти назад, но если это случилось — дубликат всё равно
        // считается дубликатом, а не «новой командой».
        assertTrue(gate.shouldProcess(command, start))
        assertFalse(gate.shouldProcess(command, start - 5_000L))
    }

    @Test
    fun resetForgetsEverything() {
        assertTrue(gate.shouldProcess(command, start))
        gate.reset()
        assertEquals(0, gate.trackedCount())
        assertTrue(gate.shouldProcess(command, start))
    }

    @Test
    fun manyDistinctCommandsWithinWindowAreAllProcessed() {
        // Карта ограничена: при переполнении старые записи удаляются, но новая команда
        // обрабатывается всегда — потерять настоящую команду хуже, чем пропустить дубликат.
        repeat(SmsCommandDedupeGate.MAX_TRACKED_COMMANDS * 2) { index ->
            assertTrue(
                "команда #$index должна обработаться",
                gate.shouldProcess("+49170|#PG:1234:STATUS$index", start + index)
            )
        }
        assertTrue(gate.trackedCount() <= SmsCommandDedupeGate.MAX_TRACKED_COMMANDS)
    }

    @Test
    fun entriesOutsideWindowAreEvicted() {
        repeat(SmsCommandDedupeGate.MAX_TRACKED_COMMANDS) { index ->
            gate.shouldProcess("+49170|#PG:1234:STATUS$index", start)
        }
        assertEquals(SmsCommandDedupeGate.MAX_TRACKED_COMMANDS, gate.trackedCount())

        // Спустя окно все старые записи просрочены: карта освобождается под новые команды.
        val later = start + SmsCommandDedupeGate.DUPLICATE_WINDOW_MS
        assertTrue(gate.shouldProcess("+49170|#PG:1234:WIPE", later))
        assertTrue(gate.trackedCount() <= SmsCommandDedupeGate.MAX_TRACKED_COMMANDS)
    }

    @Test
    fun duplicateInsideWindowStaysSuppressedForWholeWindow() {
        assertTrue(gate.shouldProcess(command, start))
        for (offset in 1 until SmsCommandDedupeGate.DUPLICATE_WINDOW_MS step 1_000L) {
            assertFalse("смещение $offset", gate.shouldProcess(command, start + offset))
        }
    }
}
