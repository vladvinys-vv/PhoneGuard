package com.phoneguard.fullscan.checks

/**
 * Признаки root-доступа и правило вердикта — без Android.
 *
 * Вынесено из [RootDetectionCheck]: там сигналы, пути и порог `rootIndicators >= 3` жили внутри
 * метода, который читает `Build.TAGS`, `PackageManager` и файловую систему, поэтому вердикт
 * сканирования не проверялся ни разу. Порог здесь важнее самих путей: ошибка в нём означает
 * либо «рутованное устройство считается чистым», либо «любое устройство объявляется рутованным».
 */
internal object RootSignals {

    /** Стандартные пути к `su`-бинарям. */
    val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/local/su",
        "/data/local/xbin/su",
        "/system/bin/.ext/su",
        "/su/bin/su",
        "/magisk/.core/bin/su"
    )

    /** Пакеты известных root-приложений (сравнение по префиксу: у них бывают варианты сборки). */
    val ROOT_PACKAGE_PREFIXES = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.thirdparty.superuser",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch"
    )

    /**
     * Маркеры перезаписанного системного раздела.
     *
     * Реальную запись в `/system` не проверяем: на заблокированном устройстве это исключение,
     * а на рутованном — необратимое изменение системы.
     */
    val SYSTEM_MARKERS = listOf(
        "/system/xbin/.su",
        "/system/bin/.ext",
        "/system/app/Superuser.apk"
    )

    /** Сколько независимых индикаторов делают устройство «рутованным». */
    const val ROOTED_THRESHOLD = 3

    /**
     * Есть ли в тегах сборки признак не-пользовательского образа.
     *
     * Сравнение по целым тегам (`release-keys,eng` → да, `avengers-keys` → нет) и **то же
     * самое правило**, что в [IntegrityPolicy.hasTestKeys]: раньше здесь искали подстроку
     * «eng», а в проверке целостности — только `test-keys`/`dev-keys`, поэтому образ с тегом
     * `eng` считался отладочным в одном отчёте и нормальным в другом.
     */
    fun hasTestKeys(buildTags: String?): Boolean =
        buildTags != null && IntegrityPolicy.hasTestKeys(buildTags)

    data class Verdict(
        val isRooted: Boolean,
        /** Доля сработавших проверок: 0.0 — ни одной, 1.0 — все. */
        val confidence: Float
    )

    /**
     * Вердикт по числу сработавших индикаторов и общему числу проверок.
     *
     * `checkCount` приводится минимум к 1: нулевое деление дало бы `NaN` в поле `confidence`,
     * а `NaN` в UI выглядит как «уверенность 100 %» после форматирования.
     */
    fun verdict(indicatorCount: Int, checkCount: Int): Verdict {
        val safeChecks = checkCount.coerceAtLeast(1)
        val safeIndicators = indicatorCount.coerceIn(0, safeChecks)
        return Verdict(
            isRooted = safeIndicators >= ROOTED_THRESHOLD,
            confidence = safeIndicators.toFloat() / safeChecks.toFloat()
        )
    }
}
