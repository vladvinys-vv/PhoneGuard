package com.phoneguard.data.repository

import com.phoneguard.data.local.SpamNumberDao
import com.phoneguard.model.SpamNumber
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpamNumberRepository @Inject constructor(
    private val spamNumberDao: SpamNumberDao
) {
    fun getAllSpamNumbers(): Flow<List<SpamNumber>> = spamNumberDao.getAllSpamNumbers()

    suspend fun insertSpamNumber(spamNumber: SpamNumber) {
        spamNumberDao.insertSpamNumber(spamNumber)
    }

    suspend fun deleteSpamNumber(id: Long) {
        spamNumberDao.deleteSpamNumber(id)
    }

    suspend fun isSpam(phoneNumber: String): Boolean {
        return spamNumberDao.isSpam(phoneNumber)
    }

    suspend fun getSpamNumber(phoneNumber: String): SpamNumber? {
        return spamNumberDao.getSpamNumber(phoneNumber)
    }
}
