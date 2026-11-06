package com.phoneguard.antitheft

/**
 * Когда [AlarmHoldService] обязан поднять тревогу заново.
 *
 * Два случая: систему попросили об этом явно (`ACTION_RESTORE`) и перезапуск процесса после
 * убийства — тогда `START_STICKY` вызывает `onStartCommand` с `intent == null`, плеера в новом
 * процессе нет, и без восстановления остаётся уведомление «сирена звучит» без звука.
 *
 * Решение вынесено из сервиса, чтобы его можно было проверить без Android: сама ветка
 * неочевидна (null-интент — это не ошибка, а сигнал), и её случайно легко «починить» в
 * обратную сторону — например, добавив `?: return`, после чего тревога перестаёт
 * восстанавливаться после перезапуска.
 */
object AlarmHoldPolicy {

    /**
     * @param intentWasNull пришёл ли `onStartCommand` без интента (перезапуск процесса)
     * @param action действие интента, если он был
     * @param restoreAction действие явного восстановления
     */
    fun shouldRestoreAlarm(
        intentWasNull: Boolean,
        action: String?,
        restoreAction: String
    ): Boolean = intentWasNull || action == restoreAction
}
