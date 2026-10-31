package com.phoneguard.util

/**
 * Признаки «спрятанного» приложения, общие для `HiddenAppsCheck` (полный скан) и
 * `SpywareCheckRepository` (экран проверки шпионского ПО).
 *
 * Раньше в репозитории действовало правило «нет launcher-интента → скрытое
 * приложение», из-за чего в список попадали наборы иконок, виджеты, плагины камеры и
 * Pro-разблокировщики: у них действительно нет иконки в лаунчере, но они безобидны.
 * Теперь приложение считается находкой, только если у него есть activity **и**
 * (опасное разрешение **или** подозрительное имя пакета).
 */
internal object HiddenAppHeuristics {

    /** Разрешения, которые делают «невидимое» приложение реально опасным. */
    val RISKY_PERMISSIONS: Set<String> = setOf(
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.BIND_DEVICE_ADMIN"
    )

    /** Имена пакетов, которые маскируются под системные службы. */
    private val SUSPICIOUS_NAME_PATTERNS = listOf(
        "com.android.system", "com.android.service",
        "com.google.service", "com.system.update",
        "update.service", "android.system"
    )

    /** Опасные разрешения, которые реально запрошены приложением. */
    fun dangerousPermissions(requestedPermissions: Set<String>): Set<String> =
        requestedPermissions.intersect(RISKY_PERMISSIONS)

    /** Имя пакета маскируется под системную службу. */
    fun hasSuspiciousName(packageName: String): Boolean =
        SUSPICIOUS_NAME_PATTERNS.any { pattern -> packageName.contains(pattern, ignoreCase = true) }

    /**
     * Стоит ли вообще рассматривать приложение как «спрятанное».
     *
     * @param activitiesCount число объявленных activity (0 — библиотека/плагин)
     */
    fun isHiddenAppCandidate(
        activitiesCount: Int,
        requestedPermissions: Set<String>,
        packageName: String
    ): Boolean {
        if (activitiesCount <= 0) return false
        return hasSuspiciousName(packageName) || dangerousPermissions(requestedPermissions).isNotEmpty()
    }
}
