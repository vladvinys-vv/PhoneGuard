package com.phoneguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_log")
data class BlockedLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val name: String? = null,
    val isSms: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
