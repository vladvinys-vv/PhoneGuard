package com.phoneguard.ui.screens.vault

import android.app.Application
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.repository.VaultRepository
import com.phoneguard.model.VaultItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
    @ApplicationContext private val context: Application
) : ViewModel() {
    val allItems = repository.allItems

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun authenticateUser(
        activity: FragmentActivity,
        executor: Executor
    ) {
        val biometricManager = BiometricManager.from(context)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            == BiometricManager.BIOMETRIC_SUCCESS
        ) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Разблокировать хранилище")
                .setSubtitle("Используйте отпечаток пальца или PIN")
                .setNegativeButtonText("Отмена")
                .build()

            val biometricPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        _isUnlocked.value = true
                    }
                }
            )
            biometricPrompt.authenticate(promptInfo)
        } else {
            _isUnlocked.value = true
        }
    }

    fun importFile(uri: Uri, deleteOriginal: Boolean = false) {
        viewModelScope.launch {
            repository.importFile(uri, deleteOriginal)
        }
    }

    fun deleteItem(item: VaultItem) {
        viewModelScope.launch {
            repository.deleteItem(item)
        }
    }
}
