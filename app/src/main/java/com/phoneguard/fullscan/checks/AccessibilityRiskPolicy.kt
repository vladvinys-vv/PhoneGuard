package com.phoneguard.fullscan.checks

/** Почему включённая служба доступности попала в отчёт. */
internal enum class AccessibilityRiskReason {
    /** Совпал подозрительный шаблон в имени пакета или ярлыка. */
    SUSPICIOUS_NAME,

    /** Пакета больше нет: служба удалённого приложения всё ещё активна. */
    REMOVED_APP,

    /** Обычная сторонняя служба с полным доступом к экрану. */
    THIRD_PARTY
}

/**
 * Порядок причин находки для служб доступности.
 *
 * Раньше это был `when` внутри [AccessibilityServiceCheck] — то есть за `PackageManager`,
 * `Settings.Secure` и `R.string`, и не проверялся. Порядок условий важен: удалённое приложение
 * с подозрительным именем сейчас описывается как «подозрительное имя» (пользователю полезнее
 * знать, что это spyware, чем что оно удалено), а обычная сторонняя служба — как третья сторона.
 */
internal object AccessibilityRiskPolicy {

    fun reasonFor(matchedPattern: String?, appLabel: String?): AccessibilityRiskReason = when {
        matchedPattern != null -> AccessibilityRiskReason.SUSPICIOUS_NAME
        appLabel == null -> AccessibilityRiskReason.REMOVED_APP
        else -> AccessibilityRiskReason.THIRD_PARTY
    }
}
