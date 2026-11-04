package com.phoneguard.simswap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Приёмник событий SIM.
 *
 * Исправления:
 *  - `BOOT_COMPLETED` объявлен в манифесте, но приёмник его игнорировал — то есть после
 *    перезагрузки телефон не «запоминал» текущую SIM, и подмена, случившаяся до первого
 *    события `SIM_STATE_CHANGED`, оставалась незамеченной. Теперь при загрузке сохраняется
 *    базовый отпечаток SIM;
 *  - работа идёт через `goAsync()` и `suspend`-вызов [SimSwapDetector.checkSimState], иначе
 *    процесс могли убить сразу после `onReceive` и сохранение отпечатка/запись события терялись.
 */
@AndroidEntryPoint
class SimSwapReceiver : BroadcastReceiver() {

    @Inject
    lateinit var detector: SimSwapDetector

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TelephonyManager.ACTION_SIM_STATE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED -> Unit
            else -> return
        }

        val pendingResult = goAsync()
        // Область уровня процесса: приёмник создаётся заново на каждый broadcast, поэтому
        // собственная область на экземпляр означала джобы, которые никто никогда не отменит.
        receiverScope.launch {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    // Фиксируем текущую SIM как базовую (детектор сам проверит разрешение).
                    detector.checkSimState()
                    return@launch
                }
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                        ?: return@launch
                val state = runCatching { telephonyManager.simState }.getOrNull()
                if (state != TelephonyManager.SIM_STATE_ABSENT &&
                    state != TelephonyManager.SIM_STATE_READY
                ) {
                    return@launch
                }
                // Проверка идёт всегда, даже если предыдущая подмена ещё не подтверждена.
                // Раньше здесь стояло `if (!detector.hasUnconfirmed())`, и флаг в DataStore
                // работал как выключатель детектора: пока пользователь не ответил на уведомление
                // (или процесс убили до его ответа), новые события SIM не проверялись вообще.
                // Повторов это не добавляет: детектор сравнивает отпечатки, а после подмены
                // эталон уже обновлён, поэтому то же состояние даёт IGNORE.
                detector.checkSimState()
            } catch (e: Exception) {
                Log.w(TAG, "SIM state handling failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SimSwapReceiver"

        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
