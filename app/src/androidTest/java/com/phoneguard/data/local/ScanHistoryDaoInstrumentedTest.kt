package com.phoneguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneguard.model.ScanHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanHistoryDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ScanHistoryDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.scanHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveScan() = runBlocking {
        val scan = ScanHistory(timestamp = 1000, riskScore = 50, issuesFound = 2, reportJson = "{}")
        dao.insertScan(scan)

        val scans = dao.getAllScans().first()
        assertEquals(1, scans.size)
        assertEquals(50, scans.first().riskScore)
    }

    @Test
    fun getLatestScanReturnsMostRecent() = runBlocking {
        dao.insertScan(ScanHistory(timestamp = 1000, riskScore = 30, issuesFound = 1))
        dao.insertScan(ScanHistory(timestamp = 2000, riskScore = 80, issuesFound = 5))

        val latest = dao.getLatestScan()
        assertNotNull(latest)
        assertEquals(80, latest!!.riskScore)
    }

    @Test
    fun deleteScanRemovesIt() = runBlocking {
        val scan = ScanHistory(timestamp = 1000, riskScore = 50, issuesFound = 2)
        val id = dao.insertScan(scan)
        dao.deleteScan(id)

        val scans = dao.getAllScans().first()
        assertTrue(scans.isEmpty())
    }

    @Test
    fun pagedScansReturnCorrectSubset() = runBlocking {
        dao.insertScan(ScanHistory(timestamp = 1000, riskScore = 10, issuesFound = 1))
        dao.insertScan(ScanHistory(timestamp = 2000, riskScore = 20, issuesFound = 2))
        dao.insertScan(ScanHistory(timestamp = 3000, riskScore = 30, issuesFound = 3))

        val page = dao.getPagedScans(limit = 2, offset = 1)
        assertEquals(2, page.size)
        assertEquals(20, page.first().riskScore)
    }
}
