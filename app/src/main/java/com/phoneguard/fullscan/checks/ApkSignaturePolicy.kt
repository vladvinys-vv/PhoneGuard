package com.phoneguard.fullscan.checks

/**
 * Правила, по которым [ApkSignatureCheck] превращает данные подписи в находки.
 *
 * Раньше вся эта логика жила внутри `performCheck()` вместе с `PackageManager`,
 * `SystemClock` и строками ресурсов, поэтому проверить её можно было только
 * Robolectric-тестом на устройстве. Здесь — чистые правила:
 *
 *  - подпись debug-ключом у приложения **из магазина** — критично (так выглядит
 *    пересборка популярного приложения «своим» ключом);
 *  - подпись debug-ключом у приложения, установленного вручную (sideload) — только
 *    предупреждение: так ставят собственные сборки, и это не признак вредоносности;
 *  - сам debuggable-сборщик подписан debug-ключом по определению — не находка;
 *  - слабый алгоритм (MD5/SHA-1) и изменённый после установки APK — предупреждения;
 *  - истёкший сертификат — информационно: Android проверяет срок только при установке.
 *
 * Порядок находок фиксирован: сначала самое серьёзное, иначе в отчёте «критичное»
 * могло оказаться ниже информационного.
 */
object ApkSignaturePolicy {

    enum class Severity { CRITICAL, WARNING, INFO }

    /** Причина находки; текст берётся из ресурсов, чтобы строки оставались переводимыми. */
    enum class Reason {
        DEBUG_KEY_FROM_STORE,
        DEBUG_KEY,
        WEAK_ALGORITHM,
        EXPIRED_CERTIFICATE,
        MODIFIED_AFTER_INSTALL
    }

    /** Всё, что нужно правилам; на устройстве заполняется из `ApkSignatureInfo` и `PackageInfo`. */
    data class State(
        val installedFromDebugKey: Boolean = false,
        val debuggable: Boolean = false,
        val installedFromStore: Boolean = false,
        val weakSignature: Boolean = false,
        val certificateExpired: Boolean = false,
        val modifiedAfterInstall: Boolean = false
    )

    data class Verdict(val reason: Reason, val severity: Severity)

    /** Установщики, которые считаются «магазином» — подпись debug-ключом там невозможна легально. */
    val STORE_INSTALLERS: Set<String> = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.huawei.appmarket",
        "com.sec.android.app.samsungapps"
    )

    /** `null` — установщик неизвестен (APK поставлен вручную); это не магазин. */
    fun isStoreInstaller(installerPackage: String?): Boolean =
        installerPackage != null && installerPackage in STORE_INSTALLERS

    fun assess(state: State): List<Verdict> {
        val verdicts = mutableListOf<Verdict>()

        // Debug-ключ у обычного (не debuggable) приложения. Владелец собственной
        // debug-сборки ничего не должен видеть.
        if (state.installedFromDebugKey && !state.debuggable) {
            verdicts += if (state.installedFromStore) {
                Verdict(Reason.DEBUG_KEY_FROM_STORE, Severity.CRITICAL)
            } else {
                Verdict(Reason.DEBUG_KEY, Severity.WARNING)
            }
        }

        if (state.weakSignature) {
            verdicts += Verdict(Reason.WEAK_ALGORITHM, Severity.WARNING)
        }

        if (state.certificateExpired) {
            verdicts += Verdict(Reason.EXPIRED_CERTIFICATE, Severity.INFO)
        }

        if (state.modifiedAfterInstall) {
            verdicts += Verdict(Reason.MODIFIED_AFTER_INSTALL, Severity.WARNING)
        }

        return verdicts
    }

    /**
     * Успевает ли проверка продолжить работу: бюджет считается по монотонным часам,
     * поэтому перевод системного времени его не растягивает.
     */
    fun withinBudget(nowElapsedMs: Long, deadlineElapsedMs: Long): Boolean =
        nowElapsedMs <= deadlineElapsedMs
}
