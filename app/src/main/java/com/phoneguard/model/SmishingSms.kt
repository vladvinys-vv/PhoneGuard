package com.phoneguard.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "smishing_sms")
data class SmishingSms(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val senderNumber: String,
    val messageBody: String,
    val extractedUrls: List<String>,
    val riskLevel: RiskLevel,
    val isFalsePositive: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "known_phishing_domains")
data class PhishingDomain(
    @PrimaryKey
    val domain: String,
    val category: String = "general",
    val addedAt: Long = System.currentTimeMillis()
)

enum class SmishingStrictness { LOW, MEDIUM, HIGH }
