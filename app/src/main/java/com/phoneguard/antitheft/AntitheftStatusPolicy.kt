package com.phoneguard.antitheft

/**
 * Правила ответа на удалённую команду `STATUS` — без Android API.
 *
 * Раньше `sendDeviceStatus()` разбирал `TelephonyManager.SIM_STATE_*` через `when` и
 * сам решал, вставлять ли строку про батарею, будучи частью приёмника SMS. Ошибка в
 * этом `when` (например, перепутанные `PIN_REQUIRED`/`PUK_REQUIRED`) видна только
 * в ответном SMS на реальном телефоне.
 *
 * Значения констант `TelephonyManager` стабильны с API 1 и здесь продублированы
 * числами, поэтому файл остаётся чистым и проверяется JVM-тестом.
 */
object AntitheftStatusPolicy {

    /** Состояние SIM в терминах ответа пользователю. */
    enum class SimState {
        READY,
        ABSENT,
        PIN_REQUIRED,
        PUK_REQUIRED,
        NETWORK_LOCKED,
        UNKNOWN
    }

    // TelephonyManager.SIM_STATE_*: UNKNOWN = 0 попадает в `else` вместе с прочими.
    private const val STATE_ABSENT = 1
    private const val STATE_PIN_REQUIRED = 2
    private const val STATE_PUK_REQUIRED = 3
    private const val STATE_NETWORK_LOCKED = 4
    private const val STATE_READY = 5

    fun simStateOf(rawValue: Int): SimState = when (rawValue) {
        STATE_READY -> SimState.READY
        STATE_ABSENT -> SimState.ABSENT
        STATE_PIN_REQUIRED -> SimState.PIN_REQUIRED
        STATE_PUK_REQUIRED -> SimState.PUK_REQUIRED
        STATE_NETWORK_LOCKED -> SimState.NETWORK_LOCKED
        // UNKNOWN (0), NOT_READY, PERM_DISABLED, CARD_IO_ERROR, LOADED и прочие
        // состояния пользователю ничего не говорят — сообщаем «неизвестно».
        else -> SimState.UNKNOWN
    }

    /**
     * Нужно ли добавлять уровень заряда в ответ.
     *
     * `BroadcastReceiver` отдаёт `-1`, если значение не пришло (нет `EXTRA_LEVEL`);
     * такой «минус один процент» в SMS выглядел бы как ошибка.
     */
    fun includeBatteryLevel(level: Int): Boolean = level in 0..100
}
