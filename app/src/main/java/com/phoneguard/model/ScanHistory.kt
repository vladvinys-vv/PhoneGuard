package com.phoneguard.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history", indices = [Index(value = ["timestamp"])])
data class ScanHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val riskScore: Int,
    val issuesFound: Int,
    val reportJson: String = ""
)

data class ScanIssue(
    val severity: IssueSeverity,
    val title: String,
    val description: String,
    val recommendation: String,
    val isHeuristic: Boolean = false,
    /**
     * Заглушка «проблем не найдено», а не настоящая находка.
     *
     * Такая запись нужна экрану отчёта (иначе список пуст и непонятно, что скан
     * завершился), но в счётчики попадать не должна: раньше чистый телефон
     * сохранялся в историю как «найдена 1 проблема» и аналитика считала единицу.
     */
    val isPlaceholder: Boolean = false
)

enum class IssueSeverity { CRITICAL, WARNING, INFO }

data class FullScanReport(
    val riskScore: Int = 0,
    val issues: List<ScanIssue> = emptyList(),
    val criticalCount: Int = 0,
    val warningCount: Int = 0,
    val infoCount: Int = 0
)
