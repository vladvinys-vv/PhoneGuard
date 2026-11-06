package com.phoneguard.antitheft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Когда сервис удержания сирены обязан поднять тревогу заново.
 *
 * `START_STICKY` перезапускает сервис с `null`-интентом. Это не ошибка, а сигнал: плеера в
 * новом процессе нет, и без восстановления пользователь видит уведомление «сирена звучит»,
 * но тишину. Правило вынесено из сервиса именно потому, что ветку легко «починить» в
 * обратную сторону — добавить `?: return` и потерять восстановление.
 */
class AlarmHoldPolicyTest {

    private val restore = "com.phoneguard.antitheft.ACTION_ALARM_HOLD_RESTORE"

    @Test
    fun `перезапуск процесса без интента требует восстановления`() {
        assertTrue(AlarmHoldPolicy.shouldRestoreAlarm(true, null, restore))
    }

    @Test
    fun `явное действие восстановления требует восстановления`() {
        assertTrue(AlarmHoldPolicy.shouldRestoreAlarm(false, restore, restore))
    }

    @Test
    fun `обычный запуск тревоги восстановление не запускает`() {
        assertFalse(
            AlarmHoldPolicy.shouldRestoreAlarm(
                false,
                "com.phoneguard.antitheft.ACTION_ALARM_HOLD_START",
                restore
            )
        )
    }

    @Test
    fun `интент без действия при явном запуске восстанавливать нечего`() {
        assertFalse(AlarmHoldPolicy.shouldRestoreAlarm(false, null, restore))
    }

    @Test
    fun `постороннее действие не считается восстановлением`() {
        assertFalse(AlarmHoldPolicy.shouldRestoreAlarm(false, restore + "_OTHER", restore))
    }
}
