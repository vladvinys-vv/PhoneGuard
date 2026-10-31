package com.phoneguard.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.phoneguard.intrusion.IntrusionSeverity
import com.phoneguard.intrusion.IntrusionType

@Entity(
    tableName = "intrusion_events",
    indices = [Index(value = ["timestamp"]), Index(value = ["severity"])]
)
data class IntrusionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: IntrusionType,
    val severity: IntrusionSeverity,
    val evidence: String = "",
    val uid: Int? = null,
    val packageName: String? = null,
    val ipAddress: String? = null,
    val photoPath: String? = null,
    val handled: Boolean = false
)
