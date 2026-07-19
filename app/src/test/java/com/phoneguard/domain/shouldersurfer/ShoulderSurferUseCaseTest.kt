package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferRepository
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
class ShoulderSurferUseCaseTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: ShoulderSurferUseCase
    private val repository: ShoulderSurferRepository = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { repository.startService() } returns Unit
        coEvery { repository.stopService() } returns Unit
        coEvery { repository.isServiceRunning() } returns true
        useCase = ShoulderSurferUseCase(repository)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `start calls repository startService`() = runTest {
        useCase.start()
        coEvery { repository.startService() } returns Unit
    }

    @Test
    fun `stop calls repository stopService`() = runTest {
        useCase.stop()
        coEvery { repository.stopService() } returns Unit
    }

    @Test
    fun `isRunning returns repository status`() = runTest {
        useCase.isRunning().collect { value ->
            assertEquals(true, value)
        }
    }
}