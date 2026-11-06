package com.phoneguard.data.shouldersurfer

import android.content.Context
import com.phoneguard.shouldersurfer.ShoulderSurferService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoulderSurferRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : ShoulderSurferController {
    /** @return `false`, если система запретила запуск сервиса. */
    override fun startService(): Boolean = ShoulderSurferService.start(context)

    override fun stopService(): Boolean = ShoulderSurferService.stop(context)
}
