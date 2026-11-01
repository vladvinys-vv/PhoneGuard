package com.phoneguard.antitheft

/**
 * Какую точность геолокации можно запросить при выданных разрешениях.
 *
 * Раньше `LocationProvider` всегда просил `PRIORITY_HIGH_ACCURACY`. Если
 * пользователь выдал только приблизительное местоположение (Android 12+ позволяет
 * выбрать «примерно»), такой запрос бросает `SecurityException`, провайдер
 * возвращал `null`, и SMS-команда `#PG:PIN:LOCATION` отвечала «координаты
 * недоступны» — хотя приблизительная точка была доступна.
 *
 * Логика вынесена из Android-класса, чтобы покрыть её JVM-тестами.
 */
internal object LocationAccuracyPolicy {

    enum class Accuracy {
        /** `ACCESS_FINE_LOCATION`: точная точка, `PRIORITY_HIGH_ACCURACY`. */
        HIGH,

        /** Только `ACCESS_COARSE_LOCATION`: городской уровень, `PRIORITY_BALANCED_POWER_ACCURACY`. */
        BALANCED,

        /** Разрешений нет — запрос не нужен. */
        NONE
    }

    fun accuracyFor(fineGranted: Boolean, coarseGranted: Boolean): Accuracy = when {
        fineGranted -> Accuracy.HIGH
        coarseGranted -> Accuracy.BALANCED
        else -> Accuracy.NONE
    }
}
