package com.phoneguard

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.intrusion.IntrusionRepository
import com.phoneguard.util.CrashHandler
import com.phoneguard.worker.DataCleanupWorker
import com.phoneguard.worker.ScheduledFullScanWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class PhoneGuardApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        net.sqlcipher.database.SQLiteDatabase.loadLibs(this)
    }

    @Inject
    lateinit var crashHandler: CrashHandler

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var intrusionRepository: IntrusionRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
        crashHandler.install()
        scheduleDataCleanup()
        scheduleScheduledFullScan()
        seedSpamDatabase()
        scanIntrusions()
    }

    private fun scanIntrusions() {
        applicationScope.launch {
            try {
                intrusionRepository.scan(this@PhoneGuardApplication)
            } catch (_: Exception) {
                // ignore intrusion scan failure
            }
        }
    }

    private fun seedSpamDatabase() {
        applicationScope.launch {
            try {
                val dao = database.spamNumberDao()
                val existing = dao.getAllSpamNumbers().first()
                if (existing.isEmpty()) {
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
