package com.phoneguard.model

/**
 * Уровни риска и признаки spyware — без Android-типов.
 *
 * Вынесены из `SpywareApp.kt` (там рядом лежит `Drawable`), чтобы скоринг риска можно было
 * прогонять юнит-тестами без Android.
 */
enum class RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

enum class SpywareIndicator {
    DEVICE_ADMIN,
    ACCESSIBILITY_SERVICE,
    SIDELOADED,
    HIDDEN_LAUNCHER,

    /** APK подписан debug-ключом — типичный признак пересборки (repackaging). */
    DEBUG_SIGNATURE,

    /** Подпись на MD5/SHA-1 — устаревший алгоритм, легко подделать. */
    WEAK_SIGNATURE,

    /** Срок действия сертификата подписи истёк. */
    EXPIRED_CERTIFICATE,

    /** APK-файл изменён после установки — возможна подмена кода. */
    MODIFIED_AFTER_INSTALL
}
