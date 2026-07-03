package com.phoneguard.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "firewall_rules")
data class FirewallRule(
    @PrimaryKey(autoGenerate = false)
    val packageName: String,
    val appName: String,
    val blockWifi: Boolean = false,
    val blockMobile: Boolean = false,
    val blockAll: Boolean = false,
    /** Блокировать весь интернет для приложения (включая VPN) */
    val blockVpn: Boolean = false,
    /** Блокировать только фоновый трафик */
    val blockBackground: Boolean = false,
    /** Блокировать определённые домены/IP */
    val blockedDomains: String = "",     // JSON array: ["example.com", "ads.net"]
    val blockedIps: String = "",         // JSON array: ["1.2.3.4", "5.6.7.8"]
    /** Режим по умолчанию: true = разрешить всё, false = заблокировать всё */
    val allowByDefault: Boolean = true,
    /** Разрешить только Wi-Fi (блокировать мобильный) */
    val allowWifiOnly: Boolean = false,
    /** Разрешить только мобильный (блокировать Wi-Fi) */
    val allowMobileOnly: Boolean = false,
    /** Timestamp последнего изменения */
    val updatedAt: Long = System.currentTimeMillis()
)
