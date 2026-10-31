package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Use case только пробрасывает результат репозитория: важно, что `Boolean`
 * (разрешила ли система запуск foreground-сервиса) не теряется по дороге —
 * на нём держится сообщение «не удалось запустить детектор» в UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoulderSurferUseCaseTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: ShoulderSurferUseCase
    private val repository: ShoulderSurferRepository = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        useCase = ShoulderSurferUseCase(repository)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun startReturnsRepositoryResult() {
        every { repository.startService() } returns true
        assertTrue(useCase.start())
        verify(exactly = 1) { repository.startService() }
    }

    @Test
    fun startReportsFailureWhenSystemBlocksService() {
        every { repository.startService() } returns false
        assertFalse(useCase.start())
    }

    @Test
    fun stopReturnsRepositoryResult() {
        every { repository.stopService() } returns false
        assertFalse(useCase.stop())
        verify(exactly = 1) { repository.stopService() }
    }

    @Test
    fun isRunningExposesRepositoryState() = runTest {
        every { repository.isServiceRunning() } returns true
        assertTrue(useCase.isRunning().first())
    }
}
