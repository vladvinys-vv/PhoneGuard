package com.phoneguard.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phoneguard.util.DataRetentionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
        } catch (e: Exception) {
            Result.retry()
        }
    }
}