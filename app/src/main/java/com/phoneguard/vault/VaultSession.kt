package com.phoneguard.vault

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Общее на всё приложение состояние «сейф разблокирован».
 *
 * Раньше флаг жил только внутри `VaultViewModel`, поэтому реакция на вторжение не могла
 * закрыть сейф: действие `LockVault` вызывало `DevicePolicyManager.lockNow()` и гасило
 * экран **всего устройства**, а без прав администратора не делало ничего вообще. Теперь
 * `LockVault` действительно запирает сейф (следующее открытие снова требует PIN/биометрию),
 * а экран устройства по-прежнему блокируется только удалённой SMS-командой `LOCK`.
 *
 * Состояние намеренно не сохраняется на диск: после перезапуска процесса сейф заперт.
 */
@Singleton
class VaultSession @Inject constructor() {

    private val _unlocked = MutableStateFlow(false)

    /** `true`, пока пользователь прошёл PIN/биометрию и не ушёл с экрана сейфа. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }
}
