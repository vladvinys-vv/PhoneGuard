package com.phoneguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpamNumberDao {
    @Query("SELECT * FROM spam_numbers WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getSpamNumber(phoneNumber: String): SpamNumber?

    @Query("SELECT EXISTS(SELECT 1 FROM spam_numbers WHERE phoneNumber = :phoneNumber)")
    suspend fun isSpam(phoneNumber: String): Boolean

    @Insert
    suspend fun insertSpamNumber(spamNumber: SpamNumber)

    @Query("DELETE FROM spam_numbers WHERE id = :id")
    suspend fun deleteSpamNumber(id: Long)
}
