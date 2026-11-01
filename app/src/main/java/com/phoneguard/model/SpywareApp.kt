package com.phoneguard.model

import android.graphics.drawable.Drawable

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
