package com.phoneguard.intrusion

/** Что делать с наблюдателем за файлами-приманками. */
internal enum class HoneypotLifecycleAction {
    /** Наблюдателя ещё нет, а защита включена — создать и запустить. */
    START,

    /** Защита выключена, а наблюдатель работает — остановить. */
    STOP,

    /** Ничего менять не нужно. */
    NONE
}

/**
 * Жизненный цикл наблюдателя за приманками.
 *
 * Раньше `ensureHoneypotObserver()` только запускал `FileObserver` и никогда его не останавливал:
 * `HoneypotObserver.stop()` существовал, но не вызывался ниоткуда. Последствия — защита
 * «выключена», а события продолжают идти: пользователь отключил приманки в настройках, но
 * `FileObserver` продолжает жить, доступы к файлам-приманкам пишутся в базу инцидентов и
 * показываются уведомления. Отключённая функция обязана молчать.
 *
 * Правило простое и потому проверяемое тестами: состояние наблюдателя всегда следует за
 * настройкой, а не за историей её изменений.
 */
internal object HoneypotLifecyclePolicy {

    fun decide(enabled: Boolean, observerRunning: Boolean): HoneypotLifecycleAction = when {
        enabled && !observerRunning -> HoneypotLifecycleAction.START
        !enabled && observerRunning -> HoneypotLifecycleAction.STOP
        else -> HoneypotLifecycleAction.NONE
    }

    /**
     * Стоит ли реагировать на событие доступа к файлу.
     *
     * Наблюдатель может успеть отдать событие уже после того, как защиту выключили (очередь
     * `inotify` и поток колбэка живут своей жизнью), поэтому проверка нужна и в самом колбэке,
     * а не только в точке запуска.
     */
    fun shouldReportAccess(enabled: Boolean, isDecoy: Boolean): Boolean = enabled && isDecoy
}
