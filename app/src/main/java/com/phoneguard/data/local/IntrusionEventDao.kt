package com.phoneguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IntrusionEventDao {
    @Insert
    suspend fun insert(event: IntrusionEventEntity): Long

    @Query("SELECT * FROM intrusion_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<IntrusionEventEntity>>

    @Query("SELECT * FROM intrusion_events WHERE handled = 0 ORDER BY timestamp DESC")
    fun observeUnhandled(): Flow<List<IntrusionEventEntity>>

    @Query("SELECT * FROM intrusion_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): IntrusionEventEntity?

    @Query("UPDATE intrusion_events SET handled = 1 WHERE id = :id")
    suspend fun markHandled(id: Long)

    @Query("UPDATE intrusion_events SET photoPath = :path WHERE id = :id")
    suspend fun updatePhotoPath(id: Long, path: String)

    @Query("SELECT * FROM intrusion_events WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<IntrusionEventEntity>

    @Query("SELECT * FROM intrusion_events WHERE timestamp < :cutoff")
    suspend fun getOlderThan(cutoff: Long): List<IntrusionEventEntity>

    @Query("DELETE FROM intrusion_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** Полная очистка журнала — вызывается пунктом «Удалить все данные». */
    @Query("DELETE FROM intrusion_events")
    suspend fun deleteAll()
}
