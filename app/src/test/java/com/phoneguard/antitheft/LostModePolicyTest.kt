package com.phoneguard.antitheft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Переходы состояния анти-кражи.
 *
 * Проверяются обе ошибки, которые здесь возможны: «врём про тревогу» (режим потери включён,
 * а звука нет) и «залипшая тревога» (после остановки флаг не снят, следующее включение
 * ничего не делает). Состояние после перезапуска процесса проверяется отдельно — это
 * именно тот случай, который раньше терялся.
 */
class LostModePolicyTest {

    @Test
    fun initialStateIsInactive() {
        val state = LostModeState.inactive()
        assertFalse(state.lostModeActive)
        assertFalse(state.alarmPlaying)
    }

    @Test
    fun restoreWithoutSavedFlagStaysInactive() {
        assertEquals(LostModeState.inactive(), LostModePolicy.onRestore(savedLostModeActive = false))
    }

    @Test
    fun restoreKeepsLostModeButNotTheAlarm() {
        // После перезапуска процесса плеера нет: режим потери остаётся, «тревога играет» — нет.
        val restored = LostModePolicy.onRestore(savedLostModeActive = true)
        assertTrue(restored.lostModeActive)
        assertFalse(restored.alarmPlaying)
    }

    @Test
    fun activationStartsAlarm() {
        val state = LostModePolicy.onActivate(LostModeState.inactive(), alarmStarted = true)
        assertTrue(state.lostModeActive)
        assertTrue(state.alarmPlaying)
    }

    @Test
    fun activationWithoutRingtoneStillEntersLostMode() {
        // MediaPlayer.create() может вернуть null (нет рингтона/аудиофокуса).
        // Режим потери от этого не отменяется — иначе SMS-команда «потерян» молча не сработает.
        val state = LostModePolicy.onActivate(LostModeState.inactive(), alarmStarted = false)
        assertTrue(state.lostModeActive)
        assertFalse(state.alarmPlaying)
    }

    @Test
    fun repeatedActivationDoesNotLoseRunningAlarm() {
        // Повторная активация (подмена SIM + ручной запуск) при уже играющей тревоге:
        // если в этот раз плеер создать не удалось, тревога всё равно продолжает играть.
        val playing = LostModeState(lostModeActive = true, alarmPlaying = true)
        val state = LostModePolicy.onActivate(playing, alarmStarted = false)
        assertTrue(state.lostModeActive)
        assertTrue(state.alarmPlaying)
    }

    @Test
    fun repeatedActivationIsIdempotent() {
        val first = LostModePolicy.onActivate(LostModeState.inactive(), alarmStarted = true)
        val second = LostModePolicy.onActivate(first, alarmStarted = true)
        assertEquals(first, second)
    }

    @Test
    fun stopAlarmClearsEverything() {
        val active = LostModeState(lostModeActive = true, alarmPlaying = true)
        assertEquals(LostModeState.inactive(), LostModePolicy.onStopAlarm(active))
    }

    @Test
    fun stopAlarmClearsLostModeEvenWhenAlarmWasNotPlaying() {
        // Режим потери включён, рингтона не было: остановка обязана снять и его,
        // иначе UI продолжит показывать «телефон потерян».
        val active = LostModeState(lostModeActive = true, alarmPlaying = false)
        assertFalse(LostModePolicy.onStopAlarm(active).lostModeActive)
    }

    @Test
    fun stopAlarmIsIdempotent() {
        val once = LostModePolicy.onStopAlarm(LostModeState(lostModeActive = true, alarmPlaying = true))
        val twice = LostModePolicy.onStopAlarm(once)
        assertEquals(once, twice)
    }

    @Test
    fun activationAfterStopWorksAgain() {
        // «Залипший флаг» проявлялся бы именно здесь: после остановки новое включение
        // ничего не делало бы.
        val started = LostModePolicy.onActivate(LostModeState.inactive(), alarmStarted = true)
        val stopped = LostModePolicy.onStopAlarm(started)
        val restarted = LostModePolicy.onActivate(stopped, alarmStarted = true)
        assertEquals(LostModeState(lostModeActive = true, alarmPlaying = true), restarted)
    }

    @Test
    fun restoreAfterStopIsInactive() {
        val stopped = LostModePolicy.onStopAlarm(LostModeState(lostModeActive = true, alarmPlaying = true))
        assertEquals(LostModeState.inactive(), LostModePolicy.onRestore(stopped.lostModeActive))
    }

    @Test
    fun restoreDoesNotResurrectAlarmForActiveMode() {
        // Отдельная формулировка того же правила: восстановление никогда не утверждает,
        // что тревога играет, даже если режим потери включён.
        val restored = LostModePolicy.onRestore(savedLostModeActive = true)
        assertFalse(restored.alarmPlaying)
        assertEquals(LostModeState(lostModeActive = true, alarmPlaying = false), restored)
    }
}
