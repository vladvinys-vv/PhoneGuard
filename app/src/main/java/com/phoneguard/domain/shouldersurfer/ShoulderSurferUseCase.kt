package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoulderSurferUseCase @Inject constructor(
    private val repository: ShoulderSurferRepository
) {
    /** @return `false`, если ОС не разрешила запуск (Android 12+, фон). */
    fun start(): Boolean = repository.startService()

    fun stop(): Boolean = repository.stopService()

    fun isRunning(): Flow<Boolean> {
        return kotlinx.coroutines.flow.flowOf(repository.isServiceRunning())
    }
}
