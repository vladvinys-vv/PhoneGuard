package com.phoneguard.fullscan.checks

import android.content.Context
import android.os.Build
import com.phoneguard.R
import java.text.ParsePosition
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
 *
 * Тексты диагностики не собираются здесь строками: проверка остаётся чистой
 * (без `Context`, чтобы её можно было гонять обычными JVM-тестами) и возвращает
 * [CheckDetail] — ресурс + аргументы. Локализацию делает [PatchResult.describe].
 */
@Singleton
class SecurityPatchCheck @Inject constructor() {

    data class PatchResult(
        /** Значение `Build.VERSION.SECURITY_PATCH` как есть; пустое — дата неизвестна. */
        val patchDate: String,
        val monthsOutdated: Int,
        val isCritical: Boolean,
        val details: List<CheckDetail>
    ) {
        /** Готовые локализованные строки для UI, логов и экспорта. */
        fun describe(context: Context): List<String> = details.resolve(context)
    }

    /** Порог устаревания в месяцах */
    private val warningThresholdMonths = 3
    private val criticalThresholdMonths = 12

    fun performCheck(): PatchResult = evaluate(
        patchDateStr = Build.VERSION.SECURITY_PATCH ?: "",
        now = System.currentTimeMillis()
    )

    /**
     * Чистая проверка (без обращения к системе) — используется в `performCheck()`
     * и в JVM-тестах `SecurityPatchCheckTest`.
     *
     * @param patchDateStr значение `Build.VERSION.SECURITY_PATCH` (`yyyy-MM-dd`)
     * @param now текущее время, мс
     */
    fun evaluate(patchDateStr: String, now: Long): PatchResult {
        val details = mutableListOf<CheckDetail>()

        if (patchDateStr.isBlank()) {
            details.add(CheckDetail(R.string.patch_detail_unknown_date))
            details.add(CheckDetail(R.string.patch_detail_unknown_hint))
            // Пустая строка вместо русского «неизвестно»: подпись подставляет
            // потребитель результата (см. `R.string.patch_date_unknown`).
            return PatchResult("", 99, true, details)
        }

        // `isLenient = false` + проверка `index == длине`: иначе "2024-13-45" и
        // "2024-01-01xyz" разбираются как «валидные» даты (SimpleDateFormat по
        // умолчанию прощает выход за диапазон и мусор в конце).
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        val patchDate = runCatching { sdf.parse(patchDateStr, position) }.getOrNull()
            ?.takeIf { position.index == patchDateStr.length }

        if (patchDate == null) {
            details.add(CheckDetail(R.string.patch_detail_unparsable, listOf(patchDateStr)))
            return PatchResult(patchDateStr, 99, true, details)
        }

        val diffMs = now - patchDate.time
        // Дата патча «из будущего» встречается при сбитых часах и у некоторых OEM:
        // отрицательное число месяцев раньше выглядело как «патч свежий».
        val monthsOutdated = (diffMs / (30L * 24 * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
        if (diffMs < 0) {
            details.add(CheckDetail(R.string.patch_detail_future))
        }

        val isCritical = monthsOutdated >= criticalThresholdMonths
        val isWarning = monthsOutdated >= warningThresholdMonths

        details.add(CheckDetail(R.string.patch_detail_date, listOf(patchDateStr)))
        details.add(CheckDetail(R.string.patch_detail_age, listOf(monthsOutdated)))

        when {
            isCritical -> details.add(
                CheckDetail(R.string.patch_detail_critical, listOf(criticalThresholdMonths))
            )
            isWarning -> details.add(
                CheckDetail(R.string.patch_detail_warning, listOf(warningThresholdMonths))
            )
            else -> details.add(CheckDetail(R.string.patch_detail_fresh))
        }

        details.add(CheckDetail(R.string.patch_detail_recommendation))

        return PatchResult(patchDateStr, monthsOutdated, isCritical, details)
    }
}
