package com.phoneguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.BlockedLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedLogDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BlockedLogDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.blockedLogDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveLogs() = runBlocking {
        dao.insertBlockedLog(BlockedLog(phoneNumber = "+79991234567", isSms = false, timestamp = 1000))
        dao.insertBlockedLog(BlockedLog(phoneNumber = "+79997654321", isSms = true, timestamp = 2000))

        val logs = dao.getAllBlockedLogs().first()
        assertEquals(2, logs.size)
    }

    @Test
    fun getBlockedCallsFiltersSms() = runBlocking {
        dao.insertBlockedLog(BlockedLog(phoneNumber = "+79991234567", isSms = false))
        dao.insertBlockedLog(BlockedLog(phoneNumber = "+79997654321", isSms = true))

        val calls = dao.getBlockedCalls().first()
        assertEquals(1, calls.size)
        assertFalse(calls.first().isSms)
    }

    @Test
    fun deleteLogRemovesIt() = runBlocking {
        val log = BlockedLog(phoneNumber = "+79991234567", isSms = false)
        dao.insertBlockedLog(log)
        dao.deleteBlockedLog(log)

        val logs = dao.getAllBlockedLogs().first()
        assertTrue(logs.isEmpty())
    }

    @Test
    fun clearAllLogsEmptiesTable() = runBlocking {
        dao.insertBlockedLog(BlockedLog(phoneNumber = "+79991234567", isSms = false))
        dao.insertBlockedLog(BlockedLog(phoneNumber = "+79997654321", isSms = true))
        dao.clearAllLogs()

        val logs = dao.getAllBlockedLogs().first()
        assertTrue(logs.isEmpty())
    }
}
