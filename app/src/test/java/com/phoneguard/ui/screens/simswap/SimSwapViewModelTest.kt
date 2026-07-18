package com.phoneguard.ui.screens.simswap

import app.cash.turbine.test
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.data.local.SimSwapEventDao
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.model.SimSwapEvent
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimSwapViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: SimSwapViewModel
    private val dao: SimSwapEventDao = mockk()
    private val preferencesManager: PreferencesManager = mockk()
    private val securityService: SecurityService = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { dao.observeAll() } returns flowOf(emptyList())
        coEvery { dao.latest() } returns null
        coEvery { preferencesManager.setSimSwapConfirmed(any()) } returns Unit
        coEvery { preferencesManager.setSimSwapUnconfirmed(any()) } returns Unit
        coEvery { securityService.activateLostMode() } returns Unit
        coEvery { securityService.sendLocationToContacts() } returns Unit
        viewModel = SimSwapViewModel(dao, preferencesManager, securityService)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `uiState has empty history when no events`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.history.isEmpty())
            assertFalse(state.isCountingDown)
        }
    }

    @Test
    fun `onConfirm emits Confirmed event and clears pending`() = runTest {
        val event = SimSwapEvent(
            id = 1,
            timestamp = System.currentTimeMillis(),
            oldImsi = "123",
            newImsi = "456",
            isConfirmed = false,
            isAttackSuspected = false
        )
        coEvery { dao.observeAll() } returns flowOf(listOf(event))
        coEvery { dao.latest() } returns event
        coEvery { dao.markConfirmed(1) } returns Unit

        viewModel = SimSwapViewModel(dao, preferencesManager, securityService)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1L, state.pendingEventId)
            assertTrue(state.isCountingDown)

            viewModel.onConfirm()

            val confirmedState = awaitItem()
            assertFalse(confirmedState.isCountingDown)
            assertEquals(null, confirmedState.pendingEventId)
        }

        coVerify { dao.markConfirmed(1) }
        coVerify { preferencesManager.setSimSwapConfirmed(true) }
        coVerify { preferencesManager.setSimSwapUnconfirmed(false) }
    }
}
