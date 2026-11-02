package com.phoneguard.fullscan.checks

/**
 * Правила «этот Device Admin подозрителен» без Android API.
 *
 * Раньше решение принималось цепочкой `return@mapNotNull` внутри `DeviceAdminAbuseCheck`
 * вместе с `DevicePolicyManager` и `PackageManager`, поэтому проверить его можно было
 * только на устройстве. Порядок проверок здесь важен и зафиксирован тестами:
 *
 *  1. свои права администратора (анти-кража самого PhoneGuard) — не находка;
 *  2. известные легитимные администраторы (MDM, Knox, Intune) — не находка;
 *  3. предустановленные (системные) администраторы — норма, а не «неизвестный admin»;
 *  4. приложение-администратор уже удалено (`appName == null`) — находка: компонент
 *     остался активным, а приложения больше нет;
 *  5. в имени пакета/приложения есть подозрительный шаблон (`admin`, `wipe`, `spy`…);
 *  6. всё остальное — сторонний администратор.
 */
object DeviceAdminRiskPolicy {

    /** Что делать с найденным администратором. */
    enum class Verdict {
        SKIP_OWN_PACKAGE,
        SKIP_KNOWN_LEGIT,
        SKIP_SYSTEM_APP,

        /** Приложение удалено, а admin-компонент остался активным. */
        APP_REMOVED,

        /** Имя/пакет похожи на инструмент слежки или «анти-воровства». */
        SUSPICIOUS_NAME,

        /** Обычный сторонний администратор. */
        THIRD_PARTY
    }

    /** Известные легитимные device admin пакеты. */
    val KNOWN_LEGIT: Set<String> = setOf(
        "com.google.android.gms",           // Google Play Services
        "com.android.settings",             // Settings
        "com.google.android.work",          // Android Enterprise
        "com.microsoft.intune",             // Intune MDM
        "com.vmware.workspace",             // VMware Workspace ONE
        "com.samsung.android.knox",         // Samsung Knox
        "com.android.enterprise"            // Android Enterprise
    )

    /** Подозрительные фрагменты имён. */
    val SUSPICIOUS_PATTERNS: List<String> = listOf(
        "admin", "device.admin", "policy",
        "security.manager", "lock", "wipe",
        "anti.theft", "spy"
    )

    fun isSuspiciousName(packageName: String, appName: String?): Boolean =
        SUSPICIOUS_PATTERNS.any { pattern ->
            packageName.contains(pattern, ignoreCase = true) ||
                (appName?.contains(pattern, ignoreCase = true) == true)
        }

    /**
     * @param packageName пакет активного администратора
     * @param appName локализованное имя; `null` — пакет не установлен (приложение удалено)
     * @param isSystemApp системное или обновлённое системное приложение
     * @param ownPackage пакет самого PhoneGuard
     */
    fun assess(
        packageName: String,
        appName: String?,
        isSystemApp: Boolean,
        ownPackage: String
    ): Verdict = when {
        packageName == ownPackage -> Verdict.SKIP_OWN_PACKAGE
        packageName in KNOWN_LEGIT -> Verdict.SKIP_KNOWN_LEGIT
        // Системное приложение без установленного имени не бывает, поэтому проверка
        // «удалено» идёт после системной: MDM производителя не должен попадать в отчёт.
        isSystemApp && appName != null -> Verdict.SKIP_SYSTEM_APP
        appName == null -> Verdict.APP_REMOVED
        isSuspiciousName(packageName, appName) -> Verdict.SUSPICIOUS_NAME
        else -> Verdict.THIRD_PARTY
    }

    /** Остаётся ли администратор в отчёте (всё, кроме пропусков). */
    fun isReportable(verdict: Verdict): Boolean = when (verdict) {
        Verdict.SKIP_OWN_PACKAGE, Verdict.SKIP_KNOWN_LEGIT, Verdict.SKIP_SYSTEM_APP -> false
        Verdict.APP_REMOVED, Verdict.SUSPICIOUS_NAME, Verdict.THIRD_PARTY -> true
    }
}
