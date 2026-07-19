package com.phoneguard.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phoneguard.fullscan.FullScanOrchestrator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ScheduledFullScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val fullScanOrchestrator: FullScanOrchestrator
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            fullScanOrchestrator.runFullScan().collect { }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}