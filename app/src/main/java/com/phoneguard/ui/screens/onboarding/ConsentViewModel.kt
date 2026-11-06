package com.phoneguard.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.preferences.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние экрана согласия: какие разрешения уже спрашивали.
 *
 * Без этой отметки «отказано навсегда» неотличимо от «ещё не спрашивали»:
 * `shouldShowRequestPermissionRationale` возвращает `false` в обоих случаях. Экран после такого
 * отказа выглядел сломанным — галочка не включалась, а объяснения не было.
 */
@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    /** Разрешения, которые приложение уже показывало в системном диалоге. */
    val askedPermissions: StateFlow<Set<String>> = preferencesManager.askedPermissions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet()
    )

    /** Запоминает, что диалог по этому разрешению уже показывался. */
    fun rememberAsked(permission: String) {
        viewModelScope.launch { preferencesManager.markPermissionAsked(permission) }
    }
}
