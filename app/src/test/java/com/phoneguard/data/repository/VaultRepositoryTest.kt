package com.phoneguard.data.repository

import app.cash.turbine.test
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.model.VaultItem
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
class VaultRepositoryTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: VaultRepository
    private val appDatabase: AppDatabase = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { appDatabase.vaultDao() } returns mockk {
            every { getAllItems() } returns flowOf(emptyList())
            coEvery { insertItem(any()) } returns 1L
            coEvery { deleteItem(any()) } returns Unit
        }
        repository = VaultRepository(appDatabase)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `allItems returns empty list initially`() = runTest {
        repository.allItems.test {
            assertEquals(emptyList<VaultItem>(), awaitItem())
        }
    }
}