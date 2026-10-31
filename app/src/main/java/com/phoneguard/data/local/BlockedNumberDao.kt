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

    @Query("DELETE FROM blocked_numbers")
    suspend fun deleteAll()

    @Query("SELECT * FROM blocked_numbers ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedBlockedNumbers(limit: Int, offset: Int): List<BlockedNumber>

    @Query("SELECT COUNT(*) FROM blocked_numbers")
    suspend fun getBlockedNumbersCount(): Int

    // --- Постраничный доступ отдельно для чёрного и белого списков ---
    // Раньше UI показывал и чёрный, и белый список одним и тем же запросом,
    // из-за чего в обоих табах отображались все номера.

    @Query("SELECT * FROM blocked_numbers WHERE isWhitelist = 0 ORDER BY phoneNumber ASC LIMIT :limit OFFSET :offset")
    suspend fun getPagedBlacklist(limit: Int, offset: Int): List<BlockedNumber>

    @Query("SELECT * FROM blocked_numbers WHERE isWhitelist = 1 ORDER BY phoneNumber ASC LIMIT :limit OFFSET :offset")
    suspend fun getPagedWhitelist(limit: Int, offset: Int): List<BlockedNumber>

    @Query("SELECT COUNT(*) FROM blocked_numbers WHERE isWhitelist = 0")
    suspend fun getBlacklistCount(): Int

    @Query("SELECT COUNT(*) FROM blocked_numbers WHERE isWhitelist = 1")
    suspend fun getWhitelistCount(): Int

    /**
     * Проверка нескольких написаний одного номера (сырой + E.164),
     * чтобы списки совпадали независимо от формата записи.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE phoneNumber IN (:phoneNumbers) AND isWhitelist = 0)")
    suspend fun isBlacklistedAny(phoneNumbers: List<String>): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE phoneNumber IN (:phoneNumbers) AND isWhitelist = 1)")
    suspend fun isWhitelistedAny(phoneNumbers: List<String>): Boolean
}
