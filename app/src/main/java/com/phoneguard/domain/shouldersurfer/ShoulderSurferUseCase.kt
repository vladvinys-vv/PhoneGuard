package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoulderSurferUseCase @Inject constructor(
    private val repository: ShoulderSurferRepository
) {
    /** @return `false`, если ОС не разрешила запуск (Android 12+, фон). */
    fun start(): Boolean = repository.startService()

    fun stop(): Boolean = repository.stopService()
}
