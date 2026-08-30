package com.phoneguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.BlockedNumber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedNumberDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BlockedNumberDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.blockedNumberDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveBlacklist() = runBlocking {
        dao.insertBlockedNumber(BlockedNumber("+79991234567", "Spam", false, System.currentTimeMillis()))
        dao.insertBlockedNumber(BlockedNumber("+79997654321", "Scam", false, System.currentTimeMillis()))

        val blacklist = dao.getBlacklist().first()
        assertEquals(2, blacklist.size)
    }

    @Test
    fun insertAndRetrieveWhitelist() = runBlocking {
        dao.insertBlockedNumber(BlockedNumber("+79991234567", "Friend", true, System.currentTimeMillis()))

        val whitelist = dao.getWhitelist().first()
        assertEquals(1, whitelist.size)
        assertTrue(whitelist.first().isWhitelist)
    }

    @Test
    fun deleteNumberRemovesIt() = runBlocking {
        val number = BlockedNumber("+79991234567", "Spam", false, System.currentTimeMillis())
        dao.insertBlockedNumber(number)
        dao.deleteBlockedNumber(number)

        val blacklist = dao.getBlacklist().first()
        assertTrue(blacklist.isEmpty())
    }
}
