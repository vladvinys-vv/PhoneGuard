package com.phoneguard.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "firewall_logs", indices = [Index(value = ["timestamp"])])
data class FirewallLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val ipAddress: String? = null,
    val domainName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val connectionType: ConnectionType,
    val trafficDirection: TrafficDirection = TrafficDirection.UNKNOWN
) {
    enum class ConnectionType { WIFI, MOBILE, UNKNOWN }
    enum class TrafficDirection { INBOUND, OUTBOUND, UNKNOWN }
}
