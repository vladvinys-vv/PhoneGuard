package com.phoneguard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.ui.navigation.PhoneGuardNavHost
import com.phoneguard.ui.screens.onboarding.OnboardingScreen
import com.phoneguard.ui.theme.PhoneGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isDarkTheme by preferencesManager.isDarkTheme.collectAsState(initial = false)
            val isFirstLaunch by preferencesManager.isFirstLaunch.collectAsState(initial = true)
            PhoneGuardTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (isFirstLaunch) {
                        OnboardingScreen(onGetStarted = {
                            runBlocking { preferencesManager.setFirstLaunchDone() }
                        })
                    } else {
                        PhoneGuardNavHost()
                    }
                }
            }
        }
    }
}
