package com.phoneguard.deviceaudit

import androidx.annotation.StringRes

/** Насколько состояние проверяемого параметра требует внимания. */
enum class AuditStatus { OK, INFO, WARNING, CRITICAL }

/** Группа проверки — используется для секций на экране. */
enum class AuditCategory { SECURITY, NETWORK, STORAGE, APPS, SYSTEM }

/**
 * Одна проверка «всего телефона».
 *
 * Тексты хранятся как ресурсы (а не готовые строки), чтобы экран отрисовал их
 * на выбранном языке. Фактические данные (версия, объём, количество) передаются
 * в [detailArgs] и подставляются в [detailRes].
 */
data class AuditItem(
    /** Стабильный идентификатор: по нему удобно искать проверку в отчётах и тестах. */
    val id: String,
    val category: AuditCategory,
    val status: AuditStatus,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    val detailArgs: List<String> = emptyList(),
    /** Что сделать пользователю; `null` для информационных строк. */
    @StringRes val recommendationRes: Int? = null
)

/** Итог проверки всего телефона. */
data class DeviceAuditReport(
    val score: Int,
    val items: List<AuditItem>,
    val timestamp: Long,
    val criticalCount: Int,
    val warningCount: Int
) {
    val issueCount: Int get() = criticalCount + warningCount

    companion object {
        /** Вес проблем при расчёте оценки (0..100). */
        const val PENALTY_CRITICAL = 15
        const val PENALTY_WARNING = 7

        /**
         * Оценка состояния устройства.
         *
         * Вынесена в чистую функцию, чтобы её можно было проверить юнит-тестом
         * без Android-окружения.
         */
        fun scoreOf(items: List<AuditItem>): Int {
            var score = 100
            items.forEach { item ->
                when (item.status) {
                    AuditStatus.CRITICAL -> score -= PENALTY_CRITICAL
                    AuditStatus.WARNING -> score -= PENALTY_WARNING
                    AuditStatus.OK, AuditStatus.INFO -> Unit
                }
            }
            return score.coerceIn(0, 100)
        }

        fun from(items: List<AuditItem>, timestamp: Long = System.currentTimeMillis()): DeviceAuditReport =
            DeviceAuditReport(
                score = scoreOf(items),
                items = items,
                timestamp = timestamp,
                criticalCount = items.count { it.status == AuditStatus.CRITICAL },
                warningCount = items.count { it.status == AuditStatus.WARNING }
            )
    }
}
