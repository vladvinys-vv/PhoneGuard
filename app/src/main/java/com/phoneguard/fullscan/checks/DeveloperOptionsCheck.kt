package com.phoneguard.fullscan.checks

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import com.phoneguard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer Options Check.
 *
 * Проверяет USB-отладку, отладку по Wi-Fi (Android 11+) и Developer Options.
 * Эти опции представляют риск, если устройство используется обычным пользователем
 * (не разработчиком), так как открывают векторы для ADB-атак.
 *
 * Решение «насколько это риск» принимает чистый [DevOptionsPolicy] — его можно
 * проверить JVM-тестом; здесь только чтение настроек и строки ресурсов.
 */
@Singleton
class DeveloperOptionsCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DevOptionsResult(
        val isUsbDebuggingEnabled: Boolean,
        val isDeveloperOptionsEnabled: Boolean,
        val riskLevel: String, // LOW / MEDIUM / HIGH
        val details: List<String>,
        /** Отладка по Wi-Fi (`adb_wifi_enabled`); до Android 11 настройки нет — `false`. */
        val isWirelessDebuggingEnabled: Boolean = false
    )

    fun performCheck(): DevOptionsResult {
        val details = mutableListOf<String>()

        val adbEnabled = isGlobalFlagEnabled(Settings.Global.ADB_ENABLED)
        // `ADB_WIFI_ENABLED` появился в Android 11 (API 30). Ключ отдельный от
        // `ADB_ENABLED`: включённая отладка по Wi-Fi раньше не попадала в отчёт.
        val wirelessSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val wirelessEnabled = wirelessSupported && isGlobalFlagEnabled(Settings.Global.ADB_WIFI_ENABLED)
        val devOptionsEnabled = isGlobalFlagEnabled(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)

        val state = DevOptionsPolicy.State(
            usbDebugging = adbEnabled,
            wirelessDebugging = wirelessEnabled,
            developerOptions = devOptionsEnabled
        )
        val riskLevel = DevOptionsPolicy.assess(state).name

        details.add(string(if (adbEnabled) R.string.dev_options_usb_on else R.string.dev_options_usb_off))
        details.add(string(if (devOptionsEnabled) R.string.dev_options_dev_on else R.string.dev_options_dev_off))
        if (wirelessSupported) {
            details.add(
                string(
                    if (wirelessEnabled) R.string.dev_options_wireless_on
                    else R.string.dev_options_wireless_off
                )
            )
        }

        if (adbEnabled) {
            details.add(string(R.string.dev_options_adb_hint))
            details.add(string(R.string.dev_options_adb_recommendation))
        }
        if (devOptionsEnabled) {
            details.add(string(R.string.dev_options_recommendation))
        }

        return DevOptionsResult(
            isUsbDebuggingEnabled = adbEnabled,
            isDeveloperOptionsEnabled = devOptionsEnabled,
            riskLevel = riskLevel,
            details = details,
            isWirelessDebuggingEnabled = wirelessEnabled
        )
    }

    /**
     * Чтение флага из `Settings.Global`.
     *
     * `SettingsNotFoundException` и `SecurityException` (некоторые прошивки закрывают
     * чтение) не должны ронять проверку — считаем флаг выключенным.
     */
    private fun isGlobalFlagEnabled(key: String): Boolean = try {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    } catch (_: Exception) {
        false
    }

    /** Отсутствующая строка не должна ронять проверку целиком. */
    private fun string(resId: Int): String = try {
        context.getString(resId)
    } catch (_: Resources.NotFoundException) {
        ""
    }
}
