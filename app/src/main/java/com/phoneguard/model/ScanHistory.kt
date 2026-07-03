package com.phoneguard.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history")
data class ScanHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val riskScore: Int,
    val issuesFound: Int,
    val reportJson: String = ""
)

data class ScanProgress(
    val step: Int,
    val totalSteps: Int,
    val stageName: String,
    val isFinished: Boolean = false
)

data class ScanIssue(
    val severity: IssueSeverity,
    val title: String,
    val description: String,
    val recommendation: String,
    val isHeuristic: Boolean = false
)

enum class IssueSeverity { CRITICAL, WARNING, INFO }

data class FullScanReport(
    val riskScore: Int = 0,
    val issues: List<ScanIssue> = emptyList(),
    val criticalCount: Int = 0,
    val warningCount: Int = 0,
    val infoCount: Int = 0
)
