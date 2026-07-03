package com.phoneguard.fullscan.checks

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer Options Check.
 *
 * Проверяет, включены ли на устройстве USB-отладка и Developer Options.
 * Эти опции представляют риск, если устройство используется обычным пользователем
 * (не разработчиком), так как открывают векторы для ADB-атак.
 */
@Singleton
class DeveloperOptionsCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DevOptionsResult(
        val isUsbDebuggingEnabled: Boolean,
        val isDeveloperOptionsEnabled: Boolean,
        val riskLevel: String, // LOW / MEDIUM / HIGH
        val details: List<String>
    )

    fun performCheck(): DevOptionsResult {
        val details = mutableListOf<String>()

        val adbEnabled = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1
        } catch (_: Exception) { false }

        val devOptionsEnabled = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
            ) == 1
        } catch (_: Exception) { false }

        val riskLevel = when {
            adbEnabled && devOptionsEnabled -> "HIGH"
            adbEnabled || devOptionsEnabled -> "MEDIUM"
            else -> "LOW"
        }

        details.add("USB-отладка: ${if (adbEnabled) "ВКЛЮЧЕНА ⚠" else "выключена"}")
        details.add("Developer Options: ${if (devOptionsEnabled) "ВКЛЮЧЕНЫ ⚠" else "выключены"}")

        if (adbEnabled) {
            details.add("USB-отладка позволяет подключаться к устройству через ADB.")
            details.add("Рекомендация: отключить USB-отладку в Настройки > Для разработчиков.")
        }
        if (devOptionsEnabled) {
            details.add("Рекомендация: отключить Developer Options, если вы не разработчик.")
        }

        return DevOptionsResult(adbEnabled, devOptionsEnabled, riskLevel, details)
    }
}
