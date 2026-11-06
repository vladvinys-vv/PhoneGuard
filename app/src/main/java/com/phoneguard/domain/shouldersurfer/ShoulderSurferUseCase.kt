package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferController
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoulderSurferUseCase @Inject constructor(
    private val controller: ShoulderSurferController
) {
    /** @return `false`, если ОС не разрешила запуск (Android 12+, фон). */
    fun start(): Boolean = controller.startService()

    fun stop(): Boolean = controller.stopService()
}
