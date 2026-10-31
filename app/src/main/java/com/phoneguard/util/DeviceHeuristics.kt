package com.phoneguard.util

import java.util.Locale

/**
 * Общие эвристики «подозрительного окружения», вынесенные из детекторов ради
 * JVM-тестов: детекторы требуют `Context`, а сами правила — чистые функции.
 *
 * Здесь же правила разбора `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`:
 * раньше их пытались разбирать `split("/")`, из-за чего имена пакетов получались
 * вида `com.pkg.SomeService:com.other.pkg`, и любая включённая служба (включая
 * TalkBack) попадала в отчёт как «удалённое приложение с активным сервисом».
 *
 * Тревога уровня `HIGH`/`RAT_DETECTED` запускает автоматическую реакцию (сирена,
 * фото, SMS доверенным контактам), поэтому цена ложного срабатывания высокая.
 */
internal object DeviceHeuristics {

    /**
     * Пакеты фреймворков для хукинга/рута. Сравнение идёт по **сегментам** имени:
     * раньше использовался `contains`, из-за чего обычное приложение
     * `com.fridays.app` считалось Frida, а `com.example.cydiabrowser` — Cydia.
     */
    private val SUSPICIOUS_SEGMENTS = setOf("frida", "xposed", "magisk", "substrate", "cydia", "lsposed")

    /** Явные пакеты, у которых подозрительный сегмент не совпадает с именем один-в-один. */
    private val SUSPICIOUS_PACKAGES = listOf(
        "re.frida.server",
        "de.robv.android.xposed",
        "com.saurik.substrate",
        "com.topjohnwu.magisk",
        "org.lsposed.manager",
        "io.va.exposed"
    )

    /** Является ли пакет известным фреймворком рута/хукинга. */
    fun isSuspiciousPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val normalized = packageName.lowercase(Locale.US)
        if (SUSPICIOUS_PACKAGES.any { normalized == it || normalized.startsWith("$it.") }) return true
        return normalized.split('.').any { segment -> segment in SUSPICIOUS_SEGMENTS }
    }

    /**
     * Пакеты включённых служб доступности.
     *
     * Значение `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — список
     * `пакет/класс`, разделённый двоеточиями. Собственный пакет исключается.
     */
    fun accessibilityPackages(
        rawSetting: String?,
        excludedPackage: String? = null
    ): List<String> {
        if (rawSetting.isNullOrBlank()) return emptyList()
        return rawSetting
            .split(':')
            .asSequence()
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
            .filterNot { excludedPackage != null && it == excludedPackage }
            .distinct()
            .toList()
    }

    /**
     * Пакеты включённых служб доступности, которые не являются системными/известными.
     */
    fun untrustedAccessibilityPackages(
        rawSetting: String?,
        ownPackageName: String?
    ): List<String> = accessibilityPackages(rawSetting, ownPackageName)
        .filterNot(::isTrustedAccessibilityPackage)

    /**
     * Системные и предустановленные службы доступности: TalkBack, Switch Access,
     * Voice Access, Samsung/OnePlus/… реализации. Сторонние службы (в том числе
     * вредоносные) по-прежнему попадут в отчёт.
     */
    fun isTrustedAccessibilityPackage(packageName: String): Boolean =
        TRUSTED_ACCESSIBILITY_PREFIXES.any { prefix ->
            packageName == prefix || packageName.startsWith(prefix)
        }

    private val TRUSTED_ACCESSIBILITY_PREFIXES = listOf(
        "android.",
        "com.android.",
        "com.google.android.marvin.",
        "com.google.android.accessibility.",
        "com.google.android.apps.accessibility.",
        "com.samsung.",
        "com.sec.android.",
        "com.oneplus.",
        "com.oplus.",
        "com.miui.",
        "com.xiaomi.",
        "com.coloros.",
        "com.transsion.",
        "com.vivo.",
        "com.huawei.",
        "com.zte."
    )
}
