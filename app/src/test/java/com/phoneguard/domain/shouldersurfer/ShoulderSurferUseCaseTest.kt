package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Use case только пробрасывает результат репозитория: важно, что `Boolean`
 * (разрешила ли система запуск foreground-сервиса) не теряется по дороге —
 * на нём держится сообщение «не удалось запустить детектор» в UI.
 */
class ShoulderSurferUseCaseTest {

    private val repository: ShoulderSurferRepository = mockk()
    private val useCase = ShoulderSurferUseCase(repository)

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
}
