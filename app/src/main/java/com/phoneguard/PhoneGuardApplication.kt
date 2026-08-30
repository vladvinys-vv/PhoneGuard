package com.phoneguard

import android.app.Application
import android.os.Build
import android.os.StrictMode
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.util.CrashHandler
import com.phoneguard.worker.DataCleanupWorker
import com.phoneguard.worker.ScheduledFullScanWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class PhoneGuardApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
        CrashHandler().install()
        scheduleDataCleanup()
        scheduleScheduledFullScan()
        seedSpamDatabase()
    }

    private fun seedSpamDatabase() {
        applicationScope.launch {
            try {
                val db = Room.databaseBuilder(
                    this@PhoneGuardApplication,
                    AppDatabase::class.java,
                    "phoneguard_db"
                ).build()
                val dao = db.spamNumberDao()
                val existing = dao.getAllSpamNumbers().let { flow ->
                    kotlinx.coroutines.flow.firstOrNull { it }
                }
                if (existing.isNullOrEmpty()) {
                    val json = assets.open("spam_numbers.json").bufferedReader().use { it.readText() }
                    val array = JSONArray(json)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val phoneNumber = obj.getString("phoneNumber")
                        val source = obj.optString("source", "seed")
                        if (!dao.isSpam(phoneNumber)) {
                            dao.insertSpamNumber(
                                com.phoneguard.model.SpamNumber(
                                    phoneNumber = phoneNumber,
                                    source = source,
                                    addedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                db.close()
            } catch (e: Exception) {
                // ignore seed failure
            }
        }
    }

    private fun scheduleDataCleanup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val cleanupRequest = PeriodicWorkRequestBuilder<DataCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "data_cleanup",
            ExistingPeriodicWorkPolicy.UPDATE,
            cleanupRequest
        )
    }

    private fun scheduleScheduledFullScan() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val scanRequest = PeriodicWorkRequestBuilder<ScheduledFullScanWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "scheduled_full_scan",
            ExistingPeriodicWorkPolicy.UPDATE,
            scanRequest
        )
    }
}
