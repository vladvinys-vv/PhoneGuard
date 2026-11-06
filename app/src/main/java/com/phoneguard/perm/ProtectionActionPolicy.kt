package com.phoneguard.perm

import com.phoneguard.protect.SensitiveAppPolicy

/**
 * Что предложить нажать, когда защита работает не полностью.
 *
 * До этого проект умел **находить** проблему и не умел её исправить: карточка на дашборде
 * перечисляла отозванные разрешения и объясняла, зачем они нужны, а кнопки не было. Человек
 * читал «камера отключена» и должен был сам вспомнить путь в системные настройки.
 *
 * Главное правило здесь — **не предлагать действие, которое ничего не сделает**: Android не
 * показывает второй системный диалог разрешения после «больше не спрашивать», и нажатие
 * «Разрешить» в этом состоянии превращается в тишину. Поэтому состояние «уже спрашивали и
 * больше не спросим» ведёт в настройки приложения, а не к диалогу.
 *
 * Модуль без Android: выбранное действие — это значение enum, а превращение его в `Intent`
 * живёт в `util/SystemSettingsIntents`. Проверяется JVM-тестами.
 */
object ProtectionActionPolicy {

    /** Что именно произойдёт по нажатию. */
    enum class Action {
        /** Системный диалог разрешения (только если он вообще может появиться). */
        REQUEST_PERMISSION,

        /** Экран приложения в системных настройках: разрешения и «больше не спрашивать». */
        OPEN_APP_DETAILS,

        /** Уведомления приложения: разрешение выдано, но показ выключен. */
        OPEN_NOTIFICATION_SETTINGS,

        /** Право «поверх других приложений» у конкретного пакета. */
        OPEN_OVERLAY_SETTINGS,

        /** Список служб доступности. */
        OPEN_ACCESSIBILITY_SETTINGS,

        /** Доступ к уведомлениям (чтение чужих уведомлений). */
        OPEN_NOTIFICATION_ACCESS_SETTINGS,

        /** Администраторы устройства. */
        OPEN_DEVICE_ADMIN_SETTINGS
    }

    /**
     * Действие для отсутствующего разрешения.
     *
     * @param askedBefore спрашивали ли мы уже это разрешение (иначе «отказ навсегда» неотличим
     *   от «ещё не спрашивали»: `shouldShowRequestPermissionRationale` в обоих случаях `false`)
     * @param canAskAgain покажет ли система диалог ещё раз
     * @return `null`, если разрешение уже выдано
     */
    fun permissionAction(granted: Boolean, askedBefore: Boolean, canAskAgain: Boolean): Action? =
        when {
            granted -> null
            !askedBefore || canAskAgain -> Action.REQUEST_PERMISSION
            else -> Action.OPEN_APP_DETAILS
        }

    /**
     * Действие для уведомлений.
     *
     * Отдельно от [permissionAction], потому что `POST_NOTIFICATIONS` — не единственная причина
     * тишины: разрешение может быть выдано, а показ уведомлений выключен в системных настройках
     * (или приглушён канал). Тогда системный диалог бессмысленен, помогает только экран
     * уведомлений приложения.
     */
    fun notificationAction(
        permissionGranted: Boolean,
        appNotificationsEnabled: Boolean,
        askedBefore: Boolean,
        canAskAgain: Boolean
    ): Action? = when {
        !appNotificationsEnabled -> Action.OPEN_NOTIFICATION_SETTINGS
        permissionGranted -> null
        !askedBefore || canAskAgain -> Action.REQUEST_PERMISSION
        else -> Action.OPEN_APP_DETAILS
    }

    /** Действие для права опасного соседа: отозвать его можно только в системных настройках. */
    fun exposureAction(capability: SensitiveAppPolicy.Capability): Action = when (capability) {
        SensitiveAppPolicy.Capability.OVERLAY -> Action.OPEN_OVERLAY_SETTINGS
        SensitiveAppPolicy.Capability.ACCESSIBILITY -> Action.OPEN_ACCESSIBILITY_SETTINGS
        SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS -> Action.OPEN_NOTIFICATION_ACCESS_SETTINGS
        SensitiveAppPolicy.Capability.DEVICE_ADMIN -> Action.OPEN_DEVICE_ADMIN_SETTINGS
    }

    /**
     * Действие для самого опасного права из списка.
     *
     * @return `null`, если прав нет (тогда и предлагать нечего)
     */
    fun exposureAction(capabilities: List<SensitiveAppPolicy.Capability>): Action? =
        capabilities.firstOrNull()?.let(::exposureAction)

    /** Ведёт ли действие в системные настройки (а не в диалог разрешения). */
    fun opensSystemSettings(action: Action): Boolean = action != Action.REQUEST_PERMISSION
}
