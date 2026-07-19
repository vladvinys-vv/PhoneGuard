package com.phoneguard.ui.screens.vault

import android.content.Context
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
    @ApplicationContext private val context: Context
) : ViewModel() {
    val allItems = repository.allItems

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    companion object {
        const val MAX_FILE_SIZE_MB = 50L
        const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024
    }

    fun authenticateUser(
        activity: FragmentActivity,
        executor: Executor
    ) {
        val biometricManager = BiometricManager.from(context)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Разблокировать хранилище")
                    .setSubtitle("Используйте отпечаток пальца или Face ID")
                    .setNegativeButtonText("Отмена")
                    .build()

                val biometricPrompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            _isUnlocked.value = true
                            _authError.value = null
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            _authError.value = errString.toString()
                        }

                        override fun onAuthenticationFailed() {
                            _authError.value = "Аутентификация не удалась"
                        }
                    }
                )
                biometricPrompt.authenticate(promptInfo)
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                _authError.value = "Биометрия недоступна. Настройте отпечаток или Face ID в системных настройках."
            }
            else -> {
                _authError.value = "Невозможно выполнить биометрическую аутентификацию"
            }
        }
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun importFile(uri: Uri, deleteOriginal: Boolean = false) {
        viewModelScope.launch {
            try {
                val size = getFileSize(context, uri)
                if (size > MAX_FILE_SIZE_BYTES) {
                    _importError.value = "Файл слишком большой. Максимальный размер: $MAX_FILE_SIZE_MB MB"
                    return@launch
                }
                repository.importFile(uri, deleteOriginal)
                _importError.value = null
            } catch (e: Exception) {
                _importError.value = "Ошибка импорта: ${e.message}"
            }
        }
    }

    fun deleteItem(item: VaultItem) {
        viewModelScope.launch {
            repository.deleteItem(item)
        }
    }

    private suspend fun getFileSize(context: Context, uri: Uri): Long {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.available().toLong()
        } ?: 0L
    }
}
