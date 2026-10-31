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
import com.phoneguard.util.DataRetentionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@HiltWorker
class DataCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataRetentionManager: DataRetentionManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            dataRetentionManager.cleanupOldLogs()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Уборка устаревших данных не удалась", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DataCleanupWorker"
        const val UNIQUE_WORK_NAME = "data_cleanup"

        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<DataCleanupWorker>(
                1, TimeUnit.DAYS
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
    }
}
