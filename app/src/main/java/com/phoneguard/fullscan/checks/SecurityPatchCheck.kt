package com.phoneguard.fullscan.checks

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Security Patch Level Check.
 *
 * Сравнивает дату security-патча устройства (Build.VERSION.SECURITY_PATCH)
 * с текущей системной датой.
 *
 * ОГРАНИЧЕНИЕ: проверка основана на дате, указанной производителем устройства.
 * На некоторых устройствах Build.VERSION.SECURITY_PATCH может быть пустым
 * или некорректным. Дата сравнения — системное время устройства.
 */
@Singleton
class SecurityPatchCheck @Inject constructor() {

    data class PatchResult(
        val patchDate: String,
        val monthsOutdated: Int,
        val isCritical: Boolean,
        val details: List<String>
    )

    /** Порог устаревания в месяцах */
    private val warningThresholdMonths = 3
    private val criticalThresholdMonths = 12

    fun performCheck(): PatchResult {
        val details = mutableListOf<String>()
        val patchDateStr = Build.VERSION.SECURITY_PATCH ?: ""

        if (patchDateStr.isBlank()) {
            details.add("Не удалось определить дату security-патча.")
            details.add("Это может быть признаком кастомной прошивки или устаревшего устройства.")
            return PatchResult("неизвестно", 99, true, details)
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val patchDate = try {
            sdf.parse(patchDateStr)
        } catch (_: Exception) { null }

        if (patchDate == null) {
            details.add("Формат даты патча не распознан: $patchDateStr")
            return PatchResult(patchDateStr, 99, true, details)
        }

        val now = System.currentTimeMillis()
        val diffMs = now - patchDate.time
        val monthsOutdated = (diffMs / (30L * 24 * 60 * 60 * 1000)).toInt()

        val isCritical = monthsOutdated >= criticalThresholdMonths
        val isWarning = monthsOutdated >= warningThresholdMonths

        details.add("Дата security-патча: $patchDateStr")
        details.add("Патчу ${monthsOutdated} мес.")

        when {
            isCritical -> details.add("⚠ КРИТИЧНО: патч устарел более чем на ${criticalThresholdMonths} мес.!!!")
            isWarning -> details.add("⚠ Внимание: патч устарел более чем на ${warningThresholdMonths} мес.")
            else -> details.add("Патч свежий — хорошо.")
        }

        details.add("")
        details.add("— Рекомендация: установите последнее обновление безопасности Google.")
        details.add("")

        return PatchResult(patchDateStr, monthsOutdated, isCritical, details)
    }
}
