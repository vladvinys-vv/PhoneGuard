package com.phoneguard.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.phoneguard.R

/**
 * Безопасный запуск `Intent`-ов.
 *
 * Зачем это нужно: экраны системных настроек (`ACTION_NOTIFICATION_LISTENER_SETTINGS`,
 * `ACTION_MANAGE_OVERLAY_PERMISSION`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, …)
 * есть не на всех прошивках — особенно на китайских ROM, где часть разделов вырезана.
 * Необработанный `ActivityNotFoundException` из `startActivity()` роняет приложение
 * в момент, когда пользователь всего лишь нажал «Открыть настройки».
 *
 * Правило проекта: любой `startActivity` идёт через [open]; это же проверяет
 * `tools/static_audit.py` (`check_start_activity`).
 */
object IntentUtils {

    /**
     * Запускает активити и не даёт приложению упасть, если её нет.
     *
     * @param context контекст (Activity или application)
     * @param intent  целевой интент; копируется, исходный не меняется
     * @param failureMessageRes строка-подсказка при неудаче
     * @return `true`, если экран удалось открыть
     */
    fun open(
        context: Context,
        intent: Intent,
        failureMessageRes: Int = R.string.settings_unavailable
    ): Boolean {
        val safeIntent = Intent(intent)
        if (context !is Activity) {
            safeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(safeIntent)
            true
        } catch (_: ActivityNotFoundException) {
            showFailure(context, failureMessageRes)
            false
        } catch (_: SecurityException) {
            // Некоторые OEM-прошивки требуют дополнительных разрешений на этот экран.
            showFailure(context, failureMessageRes)
            false
        }
    }

    private fun showFailure(context: Context, messageRes: Int) {
        runCatching {
            Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
        }
    }
}
