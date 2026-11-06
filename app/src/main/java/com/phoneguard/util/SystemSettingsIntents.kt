package com.phoneguard.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.phoneguard.perm.ProtectionActionPolicy

/**
 * Переходы в системные настройки из предупреждений о защите (Android-часть
 * [ProtectionActionPolicy]).
 *
 * Зачем отдельный объект: у каждого права свой экран, а часть действий есть не на всех
 * прошивках — `ACTION_DEVICE_ADMIN_SETTINGS` отсутствует на старых версиях, а
 * `ACTION_NOTIFICATION_LISTENER_SETTINGS` некоторые производители переименовывают. Молча
 * ничего не открыть нельзя: если экран не найден, открываем хотя бы настройки приложения —
 * оттуда пользователь дойдёт сам.
 */
object SystemSettingsIntents {

    private const val TAG = "SystemSettingsIntents"

    /**
     * Intent для действия.
     *
     * @param packageName чей экран показывать; `null` — только там, где пакет не нужен
     * @return `null` для [ProtectionActionPolicy.Action.REQUEST_PERMISSION]: диалог разрешения
     *   показывает не Intent, а запрос из Activity
     */
    fun intentFor(
        action: ProtectionActionPolicy.Action,
        packageName: String?
    ): Intent? = when (action) {
        ProtectionActionPolicy.Action.REQUEST_PERMISSION -> null
        ProtectionActionPolicy.Action.OPEN_APP_DETAILS ->
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requirePackage(packageName), null)
            )
        ProtectionActionPolicy.Action.OPEN_NOTIFICATION_SETTINGS -> Intent(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        ).putExtra(Settings.EXTRA_APP_PACKAGE, requirePackage(packageName))
        ProtectionActionPolicy.Action.OPEN_OVERLAY_SETTINGS ->
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", requirePackage(packageName), null)
            )
        ProtectionActionPolicy.Action.OPEN_ACCESSIBILITY_SETTINGS ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        ProtectionActionPolicy.Action.OPEN_NOTIFICATION_ACCESS_SETTINGS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        ProtectionActionPolicy.Action.OPEN_DEVICE_ADMIN_SETTINGS ->
            Intent(Settings.ACTION_DEVICE_ADMIN_SETTINGS)
    }

    /**
     * Открывает экран; если его нет на этой прошивке — экран приложения.
     *
     * @return `true`, если открылся именно запрошенный экран
     */
    fun open(
        context: Context,
        action: ProtectionActionPolicy.Action,
        packageName: String?
    ): Boolean {
        val intent = intentFor(action, packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Нет обработчика для $action", e)
            fallback(context, packageName ?: context.packageName)
        } catch (e: SecurityException) {
            // Некоторые прошивки объявляют активность, но не дают её открыть.
            Log.w(TAG, "Нет доступа к $action", e)
            fallback(context, packageName ?: context.packageName)
        }
    }

    private fun fallback(context: Context, packageName: String): Boolean = try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        false
    } catch (e: ActivityNotFoundException) {
        // Совсем некуда идти: пусть вызывающий покажет подсказку, а не мнимую кнопку.
        Log.w(TAG, "Нет даже экрана приложения", e)
        false
    }

    /**
     * Имя не `require`: так называется функция Kotlin stdlib, и перекрывать её внутри объекта —
     * это чужое `IllegalArgumentException` вместо осмысленного сообщения.
     */
    private fun requirePackage(packageName: String?): String =
        packageName ?: error("для этого действия нужен пакет")
}
