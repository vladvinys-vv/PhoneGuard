package com.phoneguard

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
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
    lateinit var preferencesManager: PreferencesManager

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

    /**
     * Однократный засев базы спам-номеров из `assets/spam_numbers.json`.
     *
     * Флаг в DataStore, а не «таблица пуста»: иначе удалённые пользователем записи
     * возвращались при следующем запуске приложения.
     */
    private fun seedSpamDatabase() {
        applicationScope.launch {
            try {
                if (preferencesManager.isSpamDatabaseSeeded.first()) return@launch
                val dao = database.spamNumberDao()
                val json = assets.open("spam_numbers.json").bufferedReader().use { it.readText() }
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val phoneNumber = obj.getString("phoneNumber").trim()
                    if (phoneNumber.isEmpty()) continue
                    dao.insertSpamNumber(
                        com.phoneguard.model.SpamNumber(
                            phoneNumber = phoneNumber,
                            source = obj.optString("source", "seed"),
                            addedAt = System.currentTimeMillis()
                        )
                    )
                }
                preferencesManager.setSpamDatabaseSeeded()
            } catch (e: Exception) {
                // ignore seed failure — повторим при следующем запуске
            }
        }
    }

    private fun scheduleDataCleanup() {
        DataCleanupWorker.schedule(WorkManager.getInstance(this))
    }

    /**
     * Периодическое сканирование включается только по настройке.
     *
     * Раньше работа ставилась в очередь при каждом запуске приложения, даже если
     * пользователь выключил «периодическое сканирование» — после перезапуска
     * процесса она возвращалась.
     */
    private fun scheduleScheduledFullScan() {
        applicationScope.launch {
            val enabled = runCatching { preferencesManager.isScheduledScanEnabled.first() }
                .getOrDefault(false)
            val workManager = WorkManager.getInstance(this@PhoneGuardApplication)
            if (enabled) {
                ScheduledFullScanWorker.schedule(workManager)
            } else {
                ScheduledFullScanWorker.cancel(workManager)
            }
        }
    }
}
