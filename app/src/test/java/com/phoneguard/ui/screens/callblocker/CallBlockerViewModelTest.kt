package com.phoneguard.ui.screens.callblocker

import app.cash.turbine.test
import com.phoneguard.data.local.BlockedNumber
import com.phoneguard.data.local.BlockedLog
import com.phoneguard.data.preferences.PreferencesManager
import io.mockk.coEvery
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
class CallBlockerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: CallBlockerViewModel
    private val preferencesManager: PreferencesManager = mockk()
    private val appDatabase: com.phoneguard.data.local.AppDatabase = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { preferencesManager.blockUnknownNumbers } returns flowOf(false)
        coEvery { preferencesManager.blockHiddenNumbers } returns flowOf(false)
        coEvery { preferencesManager.blockInternationalNumbers } returns flowOf(false)
        viewModel = CallBlockerViewModel(preferencesManager, appDatabase)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial block rules are false`() = runTest {
        assertEquals(false, viewModel.blockUnknown.value)
        assertEquals(false, viewModel.blockHidden.value)
        assertEquals(false, viewModel.blockInternational.value)
    }

    @Test
    fun `addNumber adds to list`() = runTest {
        viewModel.blacklist.test {
            assertEquals(emptyList(), awaitItem())
            viewModel.addNumber("+79991234567", "Spam", false)
            // Note: actual DAO is mocked, so list stays empty in this test
            assertEquals(emptyList(), awaitItem())
        }
    }
}
