package com.phoneguard.model

import android.graphics.drawable.Drawable

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

data class SpywareApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val indicators: List<SpywareIndicator>,
    val riskLevel: RiskLevel
)

data class SpywareScanResult(
    val apps: List<SpywareApp>,
    val totalRiskScore: Int,
    val deviceAdminApps: List<SpywareApp>,
    val accessibilityServices: List<SpywareApp>,
    val sideloadedApps: List<SpywareApp>,
    val hiddenApps: List<SpywareApp>
)
