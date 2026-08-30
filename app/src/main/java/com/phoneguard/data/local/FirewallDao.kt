package com.phoneguard.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.FirewallRule
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallDao {
    @Query("SELECT * FROM firewall_rules ORDER BY appName ASC")
    fun getAllRules(): Flow<List<FirewallRule>>

    @Query("SELECT * FROM firewall_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun getRuleForPackage(packageName: String): FirewallRule?

    @Upsert
    suspend fun upsertRule(rule: FirewallRule)

    @Delete
    suspend fun deleteRule(rule: FirewallRule)

    // Logs
    @Query("SELECT * FROM firewall_logs ORDER BY timestamp DESC LIMIT 500")
    fun getAllLogs(): Flow<List<FirewallLog>>

    @Query("SELECT * FROM firewall_logs ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedLogs(limit: Int, offset: Int): List<FirewallLog>

    @Query("SELECT COUNT(*) FROM firewall_logs")
    suspend fun getLogsCount(): Int

    @Insert
    suspend fun insertLog(log: FirewallLog)

    @Query("DELETE FROM firewall_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM firewall_rules")
    suspend fun clearRules()

    @Query("DELETE FROM firewall_logs WHERE timestamp < :cutoff")
    suspend fun deleteLogsOlderThan(cutoff: Long)
}
