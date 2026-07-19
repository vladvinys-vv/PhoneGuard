package com.phoneguard.ui.screens.vault

import app.cash.turbine.test
import com.phoneguard.data.repository.VaultRepository
import com.phoneguard.model.VaultItem
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: VaultViewModel
    private val repository: VaultRepository = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { repository.allItems } returns flowOf(emptyList())
        viewModel = VaultViewModel(repository, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial vault is locked`() = runTest {
        assertEquals(false, viewModel.isUnlocked.value)
    }

    @Test
    fun `initial authError is null`() = runTest {
        assertNull(viewModel.authError.value)
    }

    @Test
    fun `clearAuthError sets authError to null`() = runTest {
        viewModel.clearAuthError()
        assertNull(viewModel.authError.value)
    }

    @Test
    fun `allItems emits repository list`() = runTest {
        val items = listOf(VaultItem(1, "file1", 100, "image/jpeg", "key1", 0, true))
        every { repository.allItems } returns flowOf(items)

        viewModel.allItems.test {
            assertEquals(items, awaitItem())
        }
    }
}
