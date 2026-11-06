package com.phoneguard.data.shouldersurfer

/**
 * Что use case умеет у репозитория — и ничего больше.
 *
 * Зачем отдельный интерфейс: `ShoulderSurferUseCase` проверялся MockK-заглушкой конкретного
 * класса, а такой тест не может выполняться без MockK (и не выполняется в JVM-прогоне без
 * Android SDK). С интерфейсом хватает десятистрочного фейка, и тест проверяет ровно контракт:
 * `true`/`false` от системы не теряются по дороге в UI.
 */
interface ShoulderSurferController {

    /** @return `false`, если ОС запретила запуск foreground-сервиса. */
    fun startService(): Boolean

    fun stopService(): Boolean
}
