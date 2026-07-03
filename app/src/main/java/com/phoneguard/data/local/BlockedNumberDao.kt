package com.phoneguard.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedNumberDao {

    @Query("SELECT * FROM blocked_numbers WHERE isWhitelist = 0 ORDER BY phoneNumber ASC")
    fun getBlacklist(): Flow<List<BlockedNumber>>

    @Query("SELECT * FROM blocked_numbers WHERE isWhitelist = 1 ORDER BY phoneNumber ASC")
    fun getWhitelist(): Flow<List<BlockedNumber>>

    @Query("SELECT * FROM blocked_numbers WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getBlockedNumber(phoneNumber: String): BlockedNumber?

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE phoneNumber = :phoneNumber AND isWhitelist = 0)")
    suspend fun isBlacklisted(phoneNumber: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE phoneNumber = :phoneNumber AND isWhitelist = 1)")
    suspend fun isWhitelisted(phoneNumber: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedNumber(number: BlockedNumber)

    @Delete
    suspend fun deleteBlockedNumber(number: BlockedNumber)

    @Query("DELETE FROM blocked_numbers WHERE id = :id")
    suspend fun deleteBlockedNumberById(id: Long)
}
