package com.phoneguard.fullscan.checks

/**
 * Правила «система скомпрометирована» без Android API.
 *
 * Раньше `SystemIntegrityCheck` считал просто `compromisedIndicators >= 2`, причём все
 * признаки весили одинаково. Из-за этого:
 *  - `verifiedbootstate=orange` (загрузчик разблокирован, система не проверена) сам по
 *    себе не считался компрометацией — если `flash.locked` прочитать не удалось;
 *  - старая прошивка (2+ года) в паре с любым другим «слабым» признаком давала
 *    «скомпрометировано», хотя это вопрос обновлений, а не взлома.
 *
 * Теперь признаки разделены на **сильные** (достаточны сами по себе) и **слабые**
 * (нужны два). Кастомная прошивка без отладочных ключей по-прежнему не компрометация.
 */
object IntegrityPolicy {

    enum class Signal {
        /** Verified Boot в состоянии `orange`/`yellow` — система не прошла проверку. */
        VERIFIED_BOOT_UNSAFE,

        /** `ro.boot.flash.locked` не `1` — загрузчик разблокирован. */
        BOOTLOADER_UNLOCKED,

        /** В системном разделе найдены `su`/`Superuser`/`local.prop`. */
        SYSTEM_ROOT_FILES,

        /** `test-keys`/`userdebug`/`eng.` в fingerprint — сборка не release. */
        DEBUG_BUILD,

        /** Прошивка старше двух лет — уязвимости, но не признак взлома. */
        STALE_FIRMWARE
    }

    /** Сами по себе означают, что система не в заводском состоянии. */
    val STRONG: Set<Signal> = setOf(
        Signal.VERIFIED_BOOT_UNSAFE,
        Signal.BOOTLOADER_UNLOCKED,
        Signal.SYSTEM_ROOT_FILES,
        Signal.DEBUG_BUILD
    )

    /** `ro.boot.flash.locked` → разблокирован ли загрузчик. Неизвестное значение — нет. */
    fun isBootloaderUnlocked(flashLocked: String?): Boolean =
        flashLocked != null && flashLocked != "1"

    fun isVerifiedBootUnsafe(state: String?): Boolean =
        state == "orange" || state == "yellow"

    /** Признаки не-release сборки в `Build.TAGS`/`Build.FINGERPRINT`. */
    fun hasDebugBuildMarks(tags: String, fingerprint: String): Boolean =
        tags.contains("test-keys", ignoreCase = true) ||
            tags.contains("dev-keys", ignoreCase = true) ||
            fingerprint.contains("userdebug", ignoreCase = true) ||
            fingerprint.contains("eng.", ignoreCase = true)

    /** Кастомная прошивка — только для информационной строки в отчёте. */
    fun isCustomRom(fingerprint: String): Boolean =
        fingerprint.contains("lineage", ignoreCase = true) ||
            fingerprint.contains("custom", ignoreCase = true)

    /** Сборка старше [STALE_BUILD_YEARS] лет. */
    fun isStaleBuild(buildTimeMs: Long, nowMs: Long): Boolean {
        if (buildTimeMs <= 0L || nowMs <= buildTimeMs) return false
        val ageMs = nowMs - buildTimeMs
        return ageMs >= STALE_BUILD_YEARS * 365L * 24L * 60L * 60L * 1000L
    }

    /** Сколько лет прошло со сборки прошивки (0, если дата неизвестна или в будущем). */
    fun buildAgeYears(buildTimeMs: Long, nowMs: Long): Int {
        if (buildTimeMs <= 0L || nowMs <= buildTimeMs) return 0
        return ((nowMs - buildTimeMs) / (365L * 24L * 60L * 60L * 1000L)).toInt()
    }

    /** Компрометация: любой сильный признак или хотя бы два слабых. */
    fun isCompromised(signals: Set<Signal>): Boolean =
        signals.any { it in STRONG } || signals.count { it !in STRONG } >= 2

    const val STALE_BUILD_YEARS = 2L
}
