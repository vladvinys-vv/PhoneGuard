package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Use case только пробрасывает результат контроллера: важно, что `Boolean`
 * (разрешила ли система запуск foreground-сервиса) не теряется по дороге — на нём держится
 * сообщение «не удалось запустить детектор» в UI.
 *
 * Фейк написан руками вместо MockK: во-первых, контракт из двух методов дешевле заглушки,
 * во-вторых, тест не тянет MockK и выполняется в обычном JVM-прогоне без Android SDK.
 */
class ShoulderSurferUseCaseTest {

    private class FakeController(
        private val startResult: Boolean,
        private val stopResult: Boolean
    ) : ShoulderSurferController {
        var startCalls = 0
        var stopCalls = 0

        override fun startService(): Boolean {
            startCalls++
            return startResult
        }

        override fun stopService(): Boolean {
            stopCalls++
            return stopResult
        }
    }

    @Test
    fun startReturnsControllerResult() {
        val controller = FakeController(startResult = true, stopResult = true)
        assertTrue(ShoulderSurferUseCase(controller).start())
        assertEquals(1, controller.startCalls)
    }

    @Test
    fun startReportsFailureWhenSystemBlocksService() {
        val controller = FakeController(startResult = false, stopResult = false)
        assertFalse(ShoulderSurferUseCase(controller).start())
        assertEquals(1, controller.startCalls)
    }

    @Test
    fun stopReturnsControllerResult() {
        val controller = FakeController(startResult = true, stopResult = false)
        assertFalse(ShoulderSurferUseCase(controller).stop())
        assertEquals(1, controller.stopCalls)
        assertEquals(0, controller.startCalls)
    }
}
