package com.phoneguard.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.ui.navigation.PhoneGuardNavHost
import com.phoneguard.ui.screens.onboarding.ConsentScreen
import com.phoneguard.ui.screens.onboarding.OnboardingScreen
import com.phoneguard.ui.theme.PhoneGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Единственная Activity приложения.
 *
 * Наследуется от [FragmentActivity] (а не от `ComponentActivity`), потому что
 * `BiometricPrompt` из androidx.biometric требует FragmentActivity — раньше
 * хранилище пыталось привести Context к FragmentActivity и падало с
 * ClassCastException на реальном устройстве. FragmentActivity — наследник
 * ComponentActivity, поэтому `setContent` работает как раньше.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    /**
     * Intent с deep link (`phoneguard://…`), который нужно применить к навигации.
     * Обновляется в [onCreate] и [onNewIntent]: ярлыки на рабочем столе и внешние
     * ссылки открывают приложение, но без этого параметра граф навигации их игнорировал.
     */
    private var deepLinkIntent by mutableStateOf<Intent?>(null)

    /**
     * Применяет выбранный в настройках язык до создания ресурсов Activity.
     *
     * Раньше переключатель языка вызывал `resources.updateConfiguration()` на
     * уже работающей Activity: строки не обновлялись, а после перезапуска
     * процесса выбор терялся. Теперь язык хранится в SharedPreferences-зеркале
     * и применяется здесь — до `onCreate`.
     */
    override fun attachBaseContext(newBase: Context) {
        val language = PreferencesManager.readLanguage(newBase)
        if (language.isNullOrBlank()) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration).apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkIntent = intent?.takeIf { it.action == Intent.ACTION_VIEW }
        setContent {
            val isDarkTheme by preferencesManager.isDarkTheme.collectAsState(initial = false)
            val isFirstLaunch by preferencesManager.isFirstLaunch.collectAsState(initial = true)
            val isConsentDone by preferencesManager.isConsentDone.collectAsState(initial = false)
            val scope = rememberCoroutineScope()

            PhoneGuardTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        isFirstLaunch -> OnboardingScreen()
                        !isConsentDone -> ConsentScreen(
                            onAccept = {
                                scope.launch { preferencesManager.setConsentDone() }
                            },
                            onDecline = {
                                // Отказ от разрешений не блокирует приложение: функции,
                                // требующие разрешений, останутся выключенными.
                                scope.launch { preferencesManager.setConsentDone() }
                            }
                        )
                        else -> PhoneGuardNavHost(deepLinkIntent = deepLinkIntent)
                    }
                }
            }
        }
    }

    /** Повторное открытие по ссылке, когда Activity уже запущена (`launchMode=singleTop`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntent = intent.takeIf { it.action == Intent.ACTION_VIEW }
    }
}
