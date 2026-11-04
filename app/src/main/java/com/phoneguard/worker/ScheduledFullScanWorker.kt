package com.phoneguard.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.fullscan.FullScanOrchestrator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class ScheduledFullScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val fullScanOrchestrator: FullScanOrchestrator,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // Настройка проверяется на каждом запуске, а не только при постановке работы:
        // работа могла быть поставлена до выключения, восстановлена планировщиком или
        // остаться от прошлой версии. Полное сканирование устройства вопреки явно
        // выключенной настройке — это и есть расхождение «обещание ↔ поведение».
        val enabled = runCatching { preferencesManager.isScheduledScanEnabled.first() }
            .getOrDefault(false)
        if (ScanSchedulePolicy.verdict(enabled) == ScanSchedulePolicy.Verdict.SKIP_SETTING_DISABLED) {
            Log.d(TAG, "Периодическое сканирование выключено в настройках — пропускаем запуск")
            return Result.success()
        }
        return try {
            fullScanOrchestrator.runFullScan().collect { }
            Result.success()
        } catch (cancelled: CancellationException) {
            // Отмену WorkManager должен видеть сам: `Result.retry()` здесь
            // превратил бы остановку работы в бесконечные повторы.
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Плановое сканирование не удалось", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduledFullScanWorker"
        const val UNIQUE_WORK_NAME = "scheduled_full_scan"

        /**
         * Единственное место, где создаётся запрос периодического скана.
         * Раньше это дублировалось в `PhoneGuardApplication` и `SettingsViewModel`
         * с разными ограничениями — запросы могли разъезжаться.
         */
        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduledFullScanWorker>(
                7, TimeUnit.DAYS
            ).setConstraints(constraints)
                .build()

            // KEEP, а не UPDATE: schedule() вызывается на каждом старте приложения,
            // а UPDATE переармирует периодическую работу — при частых запусках
            // следующий запуск так и не наступал. Настройка «выключено → включено»
            // проходит через cancel() и создаёт работу заново.
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
