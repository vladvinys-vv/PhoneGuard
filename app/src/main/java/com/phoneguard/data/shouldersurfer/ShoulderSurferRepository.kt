package com.phoneguard.data.shouldersurfer

import android.content.Context
import com.phoneguard.shouldersurfer.ShoulderSurferService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoulderSurferRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** @return `false`, если система запретила запуск сервиса. */
    fun startService(): Boolean = ShoulderSurferService.start(context)

    fun stopService(): Boolean = ShoulderSurferService.stop(context)

    fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(100).any { it.service.className == ShoulderSurferService::class.java.name }
    }
}
