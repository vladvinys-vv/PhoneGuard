package com.phoneguard.antitheft

/**
 * Состояние анти-кражи: включён ли режим потери и играет ли тревога.
 *
 * Два флага намеренно разделены. Режим потери — это «телефон потерян/украден», он сохраняется
 * в настройках и переживает перезапуск процесса. Тревога — это `MediaPlayer`, который живёт
 * только пока живёт процесс: после перезапуска она не играет, даже если режим потери включён.
 * Раньше это был один флаг в памяти (`_lostModeActive`), поэтому после перезапуска приложение
 * считало режим потери выключенным, хотя настройка `remote_alarm_enabled` оставалась включённой.
 */
internal data class LostModeState(
    val lostModeActive: Boolean,
    val alarmPlaying: Boolean
) {
    companion object {
        fun inactive(): LostModeState = LostModeState(lostModeActive = false, alarmPlaying = false)
    }
}

/**
 * Переходы состояния анти-кражи — чистые функции, без `MediaPlayer`, `Vibrator` и DataStore.
 *
 * Правила закреплены тестами ([LostModePolicyTest]), потому что здесь две ошибки стоят дорого:
 *  - «врём про тревогу» — режим потери включён, а звука нет (рингтон не поставился, процесс
 *    перезапустился), и пользователь уверен, что телефон орёт;
 *  - «залипшая тревога» — `stopAlarm()` не снял флаг, и следующее включение ничего не делает.
 */
internal object LostModePolicy {

    /**
     * Восстановление после перезапуска процесса.
     *
     * Режим потери возвращается из настроек, тревога — **нет**: аудио-плеера больше нет,
     * и утверждать, что он играет, нечестно. Запускать тревогу автоматически при старте
     * процесса нельзя: сервис не является foreground-сервисом с аудио, и «тихий» старт
     * приложения не должен включать сирену.
     */
    fun onRestore(savedLostModeActive: Boolean): LostModeState =
        LostModeState(lostModeActive = savedLostModeActive, alarmPlaying = false)

    /**
     * Активация режима потери.
     *
     * Идемпотентна: повторный вызов (подмена SIM + ручной запуск + SMS-команда) не должен
     * наслаивать тревоги. Если плеер уже играет, он продолжает играть — даже если на этот раз
     * создать его не удалось.
     */
    fun onActivate(state: LostModeState, alarmStarted: Boolean): LostModeState =
        LostModeState(
            lostModeActive = true,
            alarmPlaying = alarmStarted || state.alarmPlaying
        )

    /**
     * Остановка тревоги.
     *
     * Текущий контракт приложения: `stopAlarm()` выключает и режим потери (так ведёт себя
     * существующий UI и SMS-команда). Разделение «выключить сирену, но остаться в режиме потери»
     * — отдельное продуктовое решение, не молчаливое изменение поведения.
     */
    fun onStopAlarm(state: LostModeState): LostModeState = LostModeState.inactive()
}
