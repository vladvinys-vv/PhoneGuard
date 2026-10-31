package com.phoneguard.simswap

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.phoneguard.ui.theme.PhoneGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SimSwapConfirmationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // На экране — координаты и адрес подмены SIM: запрещаем скриншоты и превью
        // в списке недавних задач.
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Экран вызывается из уведомления о подмене SIM, то есть при заблокированном
        // телефоне. Без этого пользователь увидит подтверждение только после разблокировки,
        // а обратный отсчёт до блокировки устройства уже идёт.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        setContent {
            PhoneGuardTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    com.phoneguard.ui.screens.simswap.SimSwapConfirmationScreen(
                        onConfirmed = { finish() },
                        onRejected = { finish() }
                    )
                }
            }
        }
    }
}