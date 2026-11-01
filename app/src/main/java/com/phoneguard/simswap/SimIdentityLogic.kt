package com.phoneguard.simswap

/**
 * Решение по сравнению отпечатков SIM.
 *
 * Вынесено из `SimSwapDetector` в отдельный файл: сам детектор требует
 * `TelephonyManager`, разрешений и `Context`, поэтому логика в его файле не
 * попадала в JVM-прогон и её тесты (`SimIdentityLogicTest`) молча
 * пропускались — а от этой функции зависит, заметит ли приложение подмену SIM.
 */
internal object SimIdentityLogic {

    enum class Decision {
        /** Сравнивать не с чем: идентификатор SIM недоступен. */
        IGNORE,

        /** Нет базового отпечатка (первый запуск) или он в старом формате — сохранить текущий. */
        SEED_BASELINE,

        /** Отпечаток изменился — это подмена SIM. */
        SWAPPED
    }

    fun decide(storedIdentity: String?, currentIdentity: String?): Decision {
        // SIM прочитать не удалось — сохранённый отпечаток не затираем.
        if (currentIdentity == null) return Decision.IGNORE
        if (storedIdentity == null) return Decision.SEED_BASELINE
        // Отпечаток без префикса — значение старой версии (IMSI в открытом виде) или мусор:
        // это миграция, а не подмена.
        if (!storedIdentity.startsWith(HASH_PREFIX)) return Decision.SEED_BASELINE
        val stored = storedIdentity.removePrefix(HASH_PREFIX)
        return if (stored == currentIdentity) Decision.IGNORE else Decision.SWAPPED
    }
}

/** Префикс сохранённого отпечатка: значение хранится как `sha256:<hex>`. */
internal const val HASH_PREFIX = "sha256:"
