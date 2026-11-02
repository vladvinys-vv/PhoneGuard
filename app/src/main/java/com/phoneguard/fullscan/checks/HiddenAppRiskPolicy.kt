package com.phoneguard.fullscan.checks

/**
 * Причина, по которой «спрятанное» приложение попало в отчёт.
 *
 * Раньше это был `when` из трёх ветвей внутри `HiddenAppsCheck`, смешанный с
 * `PackageManager` и `getString`, поэтому проверить приоритет причин можно было
 * только на устройстве. Приоритет важен: «подозрительное имя + опасные разрешения»
 * — это [`SUSPICIOUS_WITH_PERMISSIONS`], а не просто «подозрительное имя»
 * ([`SUSPICIOUS_NAME`]): в тексте находки тогда перечисляются конкретные разрешения.
 */
object HiddenAppRiskPolicy {

    enum class Reason {
        /** Имя/пакет маскируются под системные **и** есть опасные разрешения. */
        SUSPICIOUS_WITH_PERMISSIONS,

        /** Имя/пакет маскируются под системные. */
        SUSPICIOUS_NAME,

        /** Обычное приложение без иконки, но с опасными разрешениями. */
        PERMISSIONS_ONLY
    }

    fun reason(suspiciousName: Boolean, dangerousPermissionCount: Int): Reason = when {
        suspiciousName && dangerousPermissionCount > 0 -> Reason.SUSPICIOUS_WITH_PERMISSIONS
        suspiciousName -> Reason.SUSPICIOUS_NAME
        else -> Reason.PERMISSIONS_ONLY
    }

    /**
     * Показывать ли приложение в отчёте.
     *
     * Без скрытого имени и без единого опасного разрешения это библиотека/плагин,
     * случайно оставшийся без launcher-activity — шума в отчёте от него больше, чем пользы.
     */
    fun isReportable(suspiciousName: Boolean, dangerousPermissionCount: Int): Boolean =
        suspiciousName || dangerousPermissionCount > 0
}
