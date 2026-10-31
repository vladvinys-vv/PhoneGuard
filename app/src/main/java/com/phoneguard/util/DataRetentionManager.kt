package com.phoneguard.util

import android.content.Context
import android.util.Log
import com.phoneguard.antitheft.StealthCameraService
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.intrusion.HoneypotManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Удаляет устаревшие записи (журналы блокировок, история сканов, события вторжений)
 * и связанные с ними файлы-улики.
 *
 * Раньше здесь чистились только журналы: события `intrusion_events` и фотографии
 * из каталога `phoneguard_stealth` оставались на устройстве навсегда, хотя README
 * обещает хранение улик 90 дней. Это одновременно и приватность, и место на диске.
 */
@Singleton
class DataRetentionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase
) {

    companion object {
        private const val TAG = "DataRetentionManager"
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

        // Улики вторжений: сначала файлы, затем строки в БД (иначе останутся «сироты»).
        runCatching {
            val oldEvents = appDatabase.intrusionEventDao().getOlderThan(cutoff)
            oldEvents.forEach { event ->
                event.photoPath?.let { path -> deleteIfOwnedByApp(File(path)) }
            }
            if (oldEvents.isNotEmpty()) {
                appDatabase.intrusionEventDao().deleteOlderThan(cutoff)
            }
        }.onFailure { Log.w(TAG, "Intrusion evidence cleanup failed", it) }

        // Фотографии без записи в БД (упавший ответ на интрузию) тоже подлежат удалению.
        runCatching { deleteStaleStealthPhotos(cutoff) }
            .onFailure { Log.w(TAG, "Stealth photo cleanup failed", it) }

        // Старые приманки honeypot: в них лежат «найденные» пароли и seed-фразы,
        // держать их на устройстве дольше срока хранения улик незачем.
        runCatching { deleteStaleHoneypotDecoys(cutoff) }
            .onFailure { Log.w(TAG, "Honeypot decoy cleanup failed", it) }
    }

    /**
     * Удаляет файл, только если он лежит внутри приватных каталогов приложения.
     * `photoPath` приходит из БД, поэтому удалять по нему произвольный путь нельзя.
     */
    private fun deleteIfOwnedByApp(file: File): Boolean {
        val roots = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val owned = roots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            target.path.startsWith(rootPath + File.separator)
        }
        if (!owned) {
            Log.w(TAG, "Refusing to delete file outside app storage: ${target.path}")
            return false
        }
        return target.delete()
    }

    private fun deleteStaleHoneypotDecoys(cutoff: Long) {
        val dir = File(context.filesDir, HoneypotManager.DIR_NAME)
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() in 1 until cutoff) {
                file.delete()
            }
        }
    }

    private fun deleteStaleStealthPhotos(cutoff: Long) {
        val base = context.getExternalFilesDir(null) ?: return
        val dir = File(base, StealthCameraService.OUTPUT_DIR)
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() in 1 until cutoff) {
                file.delete()
            }
        }
    }
}