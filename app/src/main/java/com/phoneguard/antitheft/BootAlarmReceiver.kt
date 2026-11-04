package com.phoneguard.antitheft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Возобновление анти-кражи после загрузки устройства.
 *
 * Зачем это нужно: режим потери сохраняется в настройках и переживает перезапуск процесса, но
 * **звук** жил только в памяти. Достаточно было зажать кнопку питания и перезагрузить телефон,
 * чтобы сирена пропала навсегда, а приложение при следующем открытии показывало «режим потери
 * включён». Для анти-кражи это обход всей защиты одной кнопкой.
 *
 * Отдельный приёмник, а не ветка в [com.phoneguard.simswap.SimSwapReceiver]: подмена SIM и
 * восстановление тревоги — разные задачи, и падение одной не должно забирать другую.
 *
 * `goAsync()` обязателен: работа идёт в корутине, и без него процесс могли убить до старта плеера.
 */
@AndroidEntryPoint
class BootAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var securityService: SecurityService

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            // После обновления приложения процесс тоже перезапускается — сирена пропадала так же.
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val pendingResult = goAsync()
        // Область уровня процесса, а не приёмника: приёмник создаётся заново на каждый broadcast,
        // и своя область на каждый экземпляр означала бы джобы, которые никто не отменяет.
        receiverScope.launch {
            try {
                securityService.resumeAfterBoot()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot resume lost mode after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootAlarmReceiver"

        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
