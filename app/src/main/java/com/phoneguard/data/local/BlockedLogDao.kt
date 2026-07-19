package com.phoneguard.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedLogDao {

    @Query("SELECT * FROM blocked_log ORDER BY timestamp DESC")
    fun getAllBlockedLogs(): Flow<List<BlockedLog>>

    @Query("SELECT * FROM blocked_log WHERE isSms = 0 ORDER BY timestamp DESC")
    fun getBlockedCalls(): Flow<List<BlockedLog>>

    @Query("SELECT * FROM blocked_log WHERE isSms = 1 ORDER BY timestamp DESC")
    fun getBlockedSms(): Flow<List<BlockedLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedLog(log: BlockedLog)

    @Delete
    suspend fun deleteBlockedLog(log: BlockedLog)

    @Query("DELETE FROM blocked_log")
    suspend fun clearAllLogs()

    @Query("DELETE FROM blocked_log WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
