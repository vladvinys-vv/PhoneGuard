package com.phoneguard.domain.shouldersurfer

import com.phoneguard.data.shouldersurfer.ShoulderSurferRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoulderSurferUseCase @Inject constructor(
    private val repository: ShoulderSurferRepository
) {
    fun start() {
        repository.startService()
    }

    fun stop() {
        repository.stopService()
    }

    fun isRunning(): Flow<Boolean> {
        return kotlinx.coroutines.flow.flowOf(repository.isServiceRunning())
    }
}
