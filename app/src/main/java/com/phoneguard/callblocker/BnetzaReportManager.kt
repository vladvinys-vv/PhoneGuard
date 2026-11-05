package com.phoneguard.callblocker

import android.content.Context
import android.util.Log
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Сборка жалобы в Bundesnetzagentur из журнала блокировок.
 *
 * До этого `BnetzaComplaintReport` не использовался нигде: данные для него никто не собирал,
 * а причина блокировки не сохранялась. Здесь журнал превращается в официальную жалобу
 * и два файла (текст для письма и CSV для массовой подачи) в `exports/`.
 *
 * Отправить письмо приложение не может и не пытается: жалоба содержит данные пользователя,
 * и решение «отправлять» должно оставаться за ним. Файлы лежат рядом с выгрузкой журналов.
 */
@Singleton
class BnetzaReportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val preferencesManager: PreferencesManager
) {

    /** Что получилось. */
    sealed class Result {
        /** @param textFile файл письма; @param csvFile таблица для массовой подачи. */
        data class Ready(
            val incidents: Int,
            val textFile: File,
            val csvFile: File
        ) : Result()

        /** Жалобу формировать нельзя: см. [BnetzaIncidentPolicy.Issue]. */
        data class NotReady(val issues: List<BnetzaIncidentPolicy.Issue>) : Result()

        /** Не удалось записать файлы (нет внешнего хранилища, нет места). */
        object Failed : Result()
    }

    /**
     * @param days период в днях; по умолчанию последние 90 — типичный горизонт,
     *   за который BNetzA просит приложить документацию.
     */
    suspend fun generate(days: Int = DEFAULT_PERIOD_DAYS): Result = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val window = BnetzaIncidentPolicy.Window(
                fromMillis = now - days * MILLIS_PER_DAY,
                toMillis = now
            )
            val logs = appDatabase.blockedLogDao().getAllBlockedLogs().first()
            val incidents = BnetzaIncidentPolicy.selectIncidents(logs, window)

            // Номер владельца — единственный контакт, который приложение честно знает:
            // он введён как номер доверенного контакта. Почту и адрес не выдумываем.
            val reporterPhone = preferencesManager.backupNumber.first()
            val issues = BnetzaIncidentPolicy.validate(
                incidents = incidents,
                reporterName = null,
                reporterPhone = reporterPhone,
                reporterEmail = null
            )
            if (issues.isNotEmpty()) return@withContext Result.NotReady(issues)

            val report = BnetzaComplaintReport(
                reportId = BnetzaComplaintReport.generateReportId(now),
                generatedDate = SimpleDateFormat(
                    BnetzaIncidentPolicy.TIMESTAMP_PATTERN, Locale.GERMANY
                ).format(Date(now)),
                incidents = incidents,
                reporterPhone = reporterPhone
            )

            val exportDir = File(context.getExternalFilesDir(null) ?: return@withContext Result.Failed, DIR)
            if (!exportDir.exists() && !exportDir.mkdirs()) return@withContext Result.Failed

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            val textFile = File(exportDir, "bnetza_beschwerde_$stamp.txt")
            val csvFile = File(exportDir, "bnetza_beschwerde_$stamp.csv")
            textFile.writeText(report.toGermanSubmissionText())
            csvFile.writeText(report.toBnetzaCsv())
            Result.Ready(incidents = incidents.size, textFile = textFile, csvFile = csvFile)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot build BNetzA complaint", e)
            Result.Failed
        }
    }

    companion object {
        private const val TAG = "BnetzaReport"
        private const val DIR = "exports"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** Период жалобы по умолчанию, дней. */
        const val DEFAULT_PERIOD_DAYS = 90
    }
}
