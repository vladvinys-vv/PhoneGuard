package com.phoneguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_numbers")
data class BlockedNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val name: String? = null,
    val isWhitelist: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
