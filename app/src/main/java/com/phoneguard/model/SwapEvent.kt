package com.phoneguard.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sim_swap_events")
data class SimSwapEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val oldImsi: String?,
    val newImsi: String?,
    val latitude: Double?,
    val longitude: Double?,
    val addressString: String?,
    val isConfirmed: Boolean = false,
    val isAttackSuspected: Boolean = false
)