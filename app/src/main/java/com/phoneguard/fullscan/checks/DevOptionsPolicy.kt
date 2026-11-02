package com.phoneguard.fullscan.checks

/**
 * Правила оценки «параметры разработчика» — без Android API.
 *
 * Раньше `when { adb && devOptions -> "HIGH" … }` жило прямо в `DeveloperOptionsCheck`,
 * поэтому проверить его можно было только на устройстве, а результат был строкой
 * (`"HIGH"`, `"MEDIUM"`, `"LOW"`) — опечатка в сравнении не ловилась компилятором.
 *
 * Отдельно учтена **отладка по Wi-Fi** (`adb_wifi_enabled`, Android 11+): это
 * самостоятельная настройка, которая не выставляет `ADB_ENABLED`, поэтому раньше
 * включённый ADB по сети в отчёте не появлялся вообще — хотя подключиться к телефону
 * можно без кабеля и физического доступа.
 */
object DevOptionsPolicy {

    /** Уровень риска; имена совпадают с прежними строками отчёта. */
    enum class Level { LOW, MEDIUM, HIGH }

    data class State(
        val usbDebugging: Boolean = false,
        val wirelessDebugging: Boolean = false,
        val developerOptions: Boolean = false
    )

    /** Есть ли вообще доступ по ADB — по кабелю или по сети. */
    fun hasAdbAccess(state: State): Boolean = state.usbDebugging || state.wirelessDebugging

    /**
     * `HIGH` — открыт ADB при включённых параметрах разработчика (полный доступ к shell),
     * `MEDIUM` — что-то одно, `LOW` — ничего.
     */
    fun assess(state: State): Level = when {
        hasAdbAccess(state) && state.developerOptions -> Level.HIGH
        hasAdbAccess(state) || state.developerOptions -> Level.MEDIUM
        else -> Level.LOW
    }
}
