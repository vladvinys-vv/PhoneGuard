package com.phoneguard.antitheft

/**
 * Какие широковещательные сообщения означают «процесс начался заново, тревогу надо поднять».
 *
 * Правило было зашито в `when` внутри [BootAlarmReceiver], и проверить его можно было только
 * устройством. Ошибка здесь дорогая в обе стороны: если забыть `ACTION_MY_PACKAGE_REPLACED`,
 * сирена пропадает после обновления приложения; если принять слишком много действий, тревога
 * начнёт подниматься от любого постороннего broadcast'а.
 *
 * `ACTION_BOOT_COMPLETED` без разрешения `RECEIVE_BOOT_COMPLETED` не приходит вовсе, поэтому
 * политика отвечает только за выбор действий, а не за выдачу разрешений.
 */
object BootResumePolicy {

    /** Действия, после которых режим потери должен зазвучать снова. */
    val RESUME_ACTIONS: Set<String> = setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.MY_PACKAGE_REPLACED"
    )

    /**
     * @param action действие из `Intent` (`null` у приёмника не бывает, но `Intent.action`
     *   может быть не задан — тогда это не наша загрузка).
     */
    fun shouldResume(action: String?): Boolean = action != null && action in RESUME_ACTIONS
}
