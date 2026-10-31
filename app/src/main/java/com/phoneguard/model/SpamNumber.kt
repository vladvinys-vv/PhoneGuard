package com.phoneguard.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "spam_numbers", indices = [Index(value = ["phoneNumber"], unique = true)])
data class SpamNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val source: String = "local",
    val addedAt: Long = System.currentTimeMillis()
)
