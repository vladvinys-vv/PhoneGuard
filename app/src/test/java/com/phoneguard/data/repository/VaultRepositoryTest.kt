package com.phoneguard.data.repository

import app.cash.turbine.test
import com.phoneguard.data.local.VaultDao
import com.phoneguard.model.VaultItem
import com.phoneguard.vault.EncryptionManager
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
    private val vaultDao: VaultDao = mockk()
    private val encryptionManager: EncryptionManager = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        // Только то, что нужно тесту: единственная проверка читает поток элементов. Заглушки
        // insertItem/deleteItem/encryptFileToVault/deleteVaultFile здесь ничего не проверяли —
        // ни один тест эти методы не вызывал, но выглядели как покрытие записи в сейф.
        every { vaultDao.getAllItems() } returns flowOf(emptyList())
        repository = VaultRepository(vaultDao, encryptionManager)
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
