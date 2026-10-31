package com.phoneguard.fullscan.checks

import android.content.Context
import android.provider.Settings
import com.phoneguard.R
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

        details.add(
            context.getString(
                if (adbEnabled) R.string.dev_options_usb_on else R.string.dev_options_usb_off
            )
        )
        details.add(
            context.getString(
                if (devOptionsEnabled) R.string.dev_options_dev_on else R.string.dev_options_dev_off
            )
        )

        if (adbEnabled) {
            details.add(context.getString(R.string.dev_options_adb_hint))
            details.add(context.getString(R.string.dev_options_adb_recommendation))
        }
        if (devOptionsEnabled) {
            details.add(context.getString(R.string.dev_options_recommendation))
        }

        return DevOptionsResult(adbEnabled, devOptionsEnabled, riskLevel, details)
    }
}
