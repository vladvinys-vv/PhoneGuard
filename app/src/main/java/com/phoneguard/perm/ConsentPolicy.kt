package com.phoneguard.perm

/**
 * Состояние разрешения на экране согласия и что с ним делать.
 *
 * Экран согласия показывал галочки и системный диалог, но не знал, **чем закончился** отказ.
 * Отсюда три беды:
 *
 *  - после отказа «больше не спрашивать» нажатие на галочку ничего не делало: Android второй
 *    диалог не показывает, и экран выглядел сломанным;
 *  - кнопка «Принять и продолжить» требовала **все** разрешения, поэтому человек с постоянным
 *    отказом не мог пройти онбординг вообще — оставалось «Отклонить»;
 *  - не было видно, какое именно разрешение отключено: одна общая ссылка в настройки на весь
 *    экран.
 *
 * Правила отдельные и без Android, поэтому проверяются JVM-тестами: превращение состояния в
 * действие берётся из [ProtectionActionPolicy], чтобы дашборд и онбординг не разошлись.
 */
object ConsentPolicy {

    /** Чем закончилось разрешение. */
    enum class State {
        /** Разрешение выдано. */
        GRANTED,

        /** Ещё не спрашивали: галочка не нажата. */
        NOT_ASKED,

        /** Отказано, но системный диалог ещё появится. */
        DECLINED_CAN_RETRY,

        /** Отказано «больше не спрашивать»: диалога не будет, поможет только настройки. */
        DECLINED_FOREVER
    }

    /**
     * @param granted выдано ли разрешение сейчас
     * @param askedBefore спрашивали ли уже (включая отказ галочкой на этом экране)
     * @param canAskAgain покажет ли система диалог ещё раз
     */
    fun state(granted: Boolean, askedBefore: Boolean, canAskAgain: Boolean): State = when {
        granted -> State.GRANTED
        !askedBefore -> State.NOT_ASKED
        canAskAgain -> State.DECLINED_CAN_RETRY
        else -> State.DECLINED_FOREVER
    }

    /**
     * Что предложить нажать для этого состояния.
     *
     * @return `null` для выданного разрешения (нажимать нечего) и для «отказ навсегда» —
     *   вместо диалога нужен экран настроек, его и вернёт политика действий
     */
    fun action(state: State): ProtectionActionPolicy.Action? = when (state) {
        State.GRANTED -> null
        State.NOT_ASKED, State.DECLINED_CAN_RETRY -> ProtectionActionPolicy.Action.REQUEST_PERMISSION
        State.DECLINED_FOREVER -> ProtectionActionPolicy.Action.OPEN_APP_DETAILS
    }

    /** Пользователь уже дал ответ по этому разрешению (выдал или отказал). */
    fun isAnswered(state: State): Boolean = state != State.NOT_ASKED

    /**
     * Можно ли закончить онбординг.
     *
     * Достаточно, чтобы по каждому пункту был ответ: требовать «все выданы» — значит не пускать
     * дальше человека, который однажды нажал «больше не спрашивать». Это ровно тот тупик, из
     * которого единственным выходом была кнопка «Отклонить».
     */
    fun canContinue(states: List<State>): Boolean = states.all(::isAnswered)

    /** Сколько пунктов ещё ждут ответа (для подсказки на кнопке). */
    fun pendingCount(states: List<State>): Int = states.count { !isAnswered(it) }

    /** Сколько возможностей останется выключенным после онбординга. */
    fun disabledCount(states: List<State>): Int = states.count {
        it == State.DECLINED_CAN_RETRY || it == State.DECLINED_FOREVER
    }
}
