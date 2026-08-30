package com.phoneguard.util

import com.phoneguard.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataRetentionManager @Inject constructor(
    private val appDatabase: AppDatabase
) {

    companion object {
        private const val RETENTION_DAYS = 90L
        private const val RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000
    }

    fun scheduleCleanup(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            cleanupOldLogs()
        }
    }

    suspend fun cleanupOldLogs() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS

        appDatabase.blockedLogDao().deleteOlderThan(cutoff)
        appDatabase.firewallDao().deleteLogsOlderThan(cutoff)
        appDatabase.scanHistoryDao().deleteOlderThan(cutoff)
    }
}