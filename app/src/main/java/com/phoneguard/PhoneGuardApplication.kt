package com.phoneguard

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PhoneGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
