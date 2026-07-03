package com.phoneguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.phoneguard.model.ScanHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanHistoryDao {
    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    fun getAllScans(): Flow<List<ScanHistory>>

    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestScan(): ScanHistory?

    @Query("SELECT * FROM scan_history WHERE id = :id")
    suspend fun getScanById(id: Long): ScanHistory?

    @Insert
    suspend fun insertScan(scan: ScanHistory): Long

    @Query("DELETE FROM scan_history WHERE id = :id")
    suspend fun deleteScan(id: Long)
}
