package com.phoneguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.SimSwapEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimSwapEventDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SimSwapEventDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.simSwapEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveEvent() = runBlocking {
        val event = SimSwapEvent(
            timestamp = 1000,
            oldImsi = "111",
            newImsi = "222",
            latitude = 1.0,
            longitude = 2.0,
            addressString = "addr",
            isConfirmed = false,
            isAttackSuspected = false
        )
        dao.insert(event)

        val events = dao.observeAll().first()
        assertEquals(1, events.size)
        assertEquals("111", events.first().oldImsi)
    }

    @Test
    fun markConfirmedUpdatesEvent() = runBlocking {
        val event = SimSwapEvent(
            timestamp = 1000,
            oldImsi = "111",
            newImsi = "222",
            latitude = null,
            longitude = null,
            addressString = null,
            isConfirmed = false,
            isAttackSuspected = false
        )
        val id = dao.insert(event)
        dao.markConfirmed(id)

        val events = dao.observeAll().first()
        assertTrue(events.first().isConfirmed)
    }

    @Test
    fun latestReturnsMostRecentEvent() = runBlocking {
        dao.insert(SimSwapEvent(timestamp = 1000, oldImsi = "111", newImsi = "222", latitude = null, longitude = null, addressString = null))
        dao.insert(SimSwapEvent(timestamp = 2000, oldImsi = "333", newImsi = "444", latitude = null, longitude = null, addressString = null))

        val latest = dao.latest()
        assertNotNull(latest)
        assertEquals("333", latest!!.oldImsi)
    }
}
