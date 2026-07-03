package com.phoneguard.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "firewall_logs")
data class FirewallLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val ipAddress: String? = null,
    val domainName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val connectionType: ConnectionType
) {
    enum class ConnectionType { WIFI, MOBILE, UNKNOWN }
}
