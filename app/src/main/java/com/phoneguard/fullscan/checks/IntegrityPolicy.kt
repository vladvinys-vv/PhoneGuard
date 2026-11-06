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

    /**
     * Теги `Build.TAGS`, означающие не-пользовательский образ.
     *
     * Сравнение по целому тегу, а не подстрокой: `Build.TAGS` — это список через запятую
     * (`release-keys`, `test-keys,eng`), поэтому `avengers-keys` отладочной сборкой не является,
     * а вот голый тег `eng` (инженерный образ) — является. Раньше голый `eng` распознавала
     * только root-проверка (искала подстроку), а проверка целостности — нет: два отчёта об
     * одном устройстве расходились.
     */
    fun hasTestKeys(tags: String): Boolean =
        tags.split(',', ' ', '\t').any { tag ->
            val value = tag.trim()
            value.equals("test-keys", ignoreCase = true) ||
                value.equals("dev-keys", ignoreCase = true) ||
                value.equals("eng", ignoreCase = true)
        }

    /** Признаки не-release сборки в `Build.TAGS`/`Build.FINGERPRINT`. */
    fun hasDebugBuildMarks(tags: String, fingerprint: String): Boolean =
        hasTestKeys(tags) ||
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

    /**
     * Файлы, которые появляются в системе только после её перезаписи.
     *
     * `/system/bin/debuggerd` здесь намеренно **нет**: он есть на каждом устройстве, и раньше
     * из-за него отчёт по целостности кричал «найден подозрительный файл» на стоковой прошивке.
     *
     * Список публичный, потому что это данные, а не решение: тест проверяет, что проверяются
     * именно эти пути, а не «сколько-то путей».
     */
    val ROOT_INDICATOR_PATHS: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/xbin/daemonsu",
        "/system/app/Superuser.apk",
        "/data/local.prop"
    )

    /** Какие из [ROOT_INDICATOR_PATHS] действительно есть. Порядок списка сохраняется. */
    fun foundRootIndicators(exists: (String) -> Boolean): List<String> =
        ROOT_INDICATOR_PATHS.filter(exists)

    /**
     * Значение системного свойства из вывода `getprop`.
     *
     * `getprop` печатает перевод строки даже для установленного свойства, а для отсутствующего —
     * пустую строку. Без `trim` свойство `ro.boot.flash.locked` сравнивалось с `"1"` как
     * `"1\n"`, то есть «загрузчик разблокирован» на заблокированном устройстве.
     */
    fun propertyValue(raw: String?): String? = raw?.trim()?.ifBlank { null }

    /** Компрометация: любой сильный признак или хотя бы два слабых. */
    fun isCompromised(signals: Set<Signal>): Boolean =
        signals.any { it in STRONG } || signals.count { it !in STRONG } >= 2

    const val STALE_BUILD_YEARS = 2L
}
