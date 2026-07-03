package com.phoneguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.phoneguard.model.SimSwapEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface SimSwapEventDao {
    @Query("SELECT * FROM sim_swap_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<SimSwapEvent>>

    @Query("SELECT * FROM sim_swap_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): SimSwapEvent?

    @Insert
    suspend fun insert(event: SimSwapEvent)

    @Query("UPDATE sim_swap_events SET isConfirmed = 1 WHERE id = :id")
    suspend fun markConfirmed(id: Long)

    @Query("UPDATE sim_swap_events SET isAttackSuspected = 1 WHERE id = :id")
    suspend fun markAttackSuspected(id: Long)
}