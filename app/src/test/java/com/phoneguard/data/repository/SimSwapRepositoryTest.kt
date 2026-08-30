package com.phoneguard.data.repository

import app.cash.turbine.test
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.model.ScanHistory
import com.phoneguard.model.SimSwapEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimSwapRepositoryTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: SimSwapRepository
    private val appDatabase: AppDatabase = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { appDatabase.simSwapEventDao() } returns mockk {
            every { observeAll() } returns flowOf(emptyList())
            coEvery { insert(any()) } returns Unit
            coEvery { markConfirmed(any()) } returns Unit
            coEvery { markAttackSuspected(any()) } returns Unit
        }
        repository = SimSwapRepository(appDatabase)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `observeEvents returns empty list initially`() = runTest {
        repository.observeEvents().test {
            assertEquals(emptyList<SimSwapEvent>(), awaitItem())
        }
    }
}