package com.phoneguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.SpamNumber
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpamNumberDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SpamNumberDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.spamNumberDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveSpamNumber() = runBlocking {
        dao.insertSpamNumber(SpamNumber(phoneNumber = "+74950000000", source = "seed"))

        val spam = dao.getSpamNumber("+74950000000")
        assertNotNull(spam)
        assertEquals("+74950000000", spam!!.phoneNumber)
    }

    @Test
    fun isSpamReturnsTrueForExistingNumber() = runBlocking {
        dao.insertSpamNumber(SpamNumber(phoneNumber = "+74950000000", source = "seed"))

        assertTrue(dao.isSpam("+74950000000"))
        assertFalse(dao.isSpam("+74950000001"))
    }

    @Test
    fun deleteSpamNumberRemovesIt() = runBlocking {
        val spam = SpamNumber(phoneNumber = "+74950000000", source = "seed")
        val id = dao.insertSpamNumber(spam)
        dao.deleteSpamNumber(id)

        val found = dao.getSpamNumber("+74950000000")
        assertNull(found)
    }
}
