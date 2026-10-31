package com.phoneguard.ui.screens.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.repository.VaultRepository
import com.phoneguard.model.VaultItem
import com.phoneguard.util.AnalyticsHelper
import com.phoneguard.vault.VaultFileTooLargeException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Ошибки хранилища в виде кодов, а не готовых строк.
 *
 * Раньше ViewModel складывала сюда русский текст — он не переводился и не
 * следовал за выбранным языком. Тексты подставляет экран через `stringResource`.
 */
sealed class VaultError {
    /** Пользователь не прошёл биометрию (неверный отпечаток и т.п.). */
    object AuthenticationFailed : VaultError()

    /** Системное сообщение биометрии (например, слишком много попыток). */
    data class PlatformFailure(val text: String?) : VaultError()

    /** Датчика нет / не настроен / временно недоступен. */
    object BiometricUnavailable : VaultError()

    /** Прочие ошибки `BiometricManager`. */
    object AuthenticationNotPossible : VaultError()

    /** Файл больше [VaultViewModel.MAX_FILE_SIZE_MB]. */
    object FileTooLarge : VaultError()

    /** Ошибка чтения/шифрования; [text] — системное сообщение, если есть. */
    data class ImportFailure(val text: String?) : VaultError()

    /** Не удалось расшифровать файл или отдать его просмотрщику. */
    object PreviewFailure : VaultError()
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
    @ApplicationContext private val context: Context,
    private val analyticsHelper: AnalyticsHelper
) : ViewModel() {
    val allItems = repository.allItems

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _authError = MutableStateFlow<VaultError?>(null)
    val authError: StateFlow<VaultError?> = _authError.asStateFlow()

    private val _importError = MutableStateFlow<VaultError?>(null)
    val importError: StateFlow<VaultError?> = _importError.asStateFlow()

    private val _previewError = MutableStateFlow<VaultError?>(null)
    val previewError: StateFlow<VaultError?> = _previewError.asStateFlow()

    /** Готовый `ACTION_VIEW`-интент на расшифрованную копию; экран запускает и сбрасывает. */
    private val _previewIntent = MutableStateFlow<Intent?>(null)
    val previewIntent: StateFlow<Intent?> = _previewIntent.asStateFlow()

    companion object {
        private const val TAG = "VaultViewModel"
        const val MAX_FILE_SIZE_MB = 50L
        const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024
    }

    /**
     * @param promptTitle/promptSubtitle/promptNegative тексты диалога биометрии —
     * передаются экраном, чтобы они были локализованы.
     */
    fun authenticateUser(
        activity: FragmentActivity,
        executor: Executor,
        promptTitle: String,
        promptSubtitle: String,
        promptNegative: String
    ) {
        val biometricManager = BiometricManager.from(context)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(promptTitle)
                    .setSubtitle(promptSubtitle)
                    .setNegativeButtonText(promptNegative)
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
                            // Пользователь сам закрыл диалог — это не ошибка.
                            if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                errorCode == BiometricPrompt.ERROR_CANCELED
                            ) {
                                _authError.value = null
                            } else {
                                _authError.value = VaultError.PlatformFailure(errString.toString())
                            }
                        }

                        override fun onAuthenticationFailed() {
                            _authError.value = VaultError.AuthenticationFailed
                        }
                    }
                )
                biometricPrompt.authenticate(promptInfo)
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                // Палец не зарегистрирован — пробуем код/графический ключ устройства.
                // Раньше сейф в этом случае был недоступен навсегда: биометрия есть,
                // но не настроена, и открыть файлы было невозможно.
                authenticateWithDeviceCredential(activity, executor, promptTitle, promptSubtitle)
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                _authError.value = VaultError.BiometricUnavailable
            }
            else -> {
                _authError.value = VaultError.AuthenticationNotPossible
            }
        }
    }

    /**
     * Запасной путь: подтверждение кодом/графическим ключом устройства.
     *
     * `DEVICE_CREDENTIAL` нельзя комбинировать с текстом негативной кнопки, поэтому
     * диалог собирается отдельно.
     */
    private fun authenticateWithDeviceCredential(
        activity: FragmentActivity,
        executor: Executor,
        promptTitle: String,
        promptSubtitle: String
    ) {
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            _authError.value = VaultError.BiometricUnavailable
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle)
            .setSubtitle(promptSubtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _isUnlocked.value = true
                    _authError.value = null
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    _authError.value = if (
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        null
                    } else {
                        VaultError.PlatformFailure(errString.toString())
                    }
                }
            }
        ).authenticate(promptInfo)
    }

    /** Мгновенная блокировка сейфа (уход приложения в фон, выход с экрана). */
    fun lock() {
        _isUnlocked.value = false
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun clearPreviewError() {
        _previewError.value = null
    }

    /**
     * Открывает файл из сейфа во внешнем просмотрщике.
     *
     * Раньше кнопка предпросмотра была заглушкой (пустой обработчик `onClick`), то есть
     * файл можно было добавить в сейф, но нельзя было открыть — хранилище работало «в одну
     * сторону».
     *
     * Расшифрованная копия лежит в `cacheDir/vault_preview` и отдаётся через `FileProvider`
     * с одноразовым правом чтения: сам `content://`-URI живёт только на время просмотра.
     */
    fun previewItem(item: VaultItem) {
        viewModelScope.launch {
            _previewError.value = null
            val file = try {
                repository.decryptFileFromVault(item)
            } catch (e: Exception) {
                null
            }
            if (file == null) {
                _previewError.value = VaultError.PreviewFailure
                return@launch
            }
            val uri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }.getOrNull()
            if (uri == null) {
                _previewError.value = VaultError.PreviewFailure
                return@launch
            }
            _previewIntent.value = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Экран уже запустил интент — сбрасываем, чтобы не открывать его повторно. */
    fun consumePreview() {
        _previewIntent.value = null
    }

    fun importFile(uri: Uri, deleteOriginal: Boolean = false) {
        viewModelScope.launch {
            try {
                // Размер может быть неизвестен (size == null) — тогда лимит
                // проверяется по фактически прочитанным байтам ниже.
                val size = getFileSize(context, uri)
                if (size != null && size > MAX_FILE_SIZE_BYTES) {
                    _importError.value = VaultError.FileTooLarge
                    return@launch
                }
                repository.importFile(uri, deleteOriginal)
                _importError.value = null
                analyticsHelper.logVaultImport(
                    fileSize = size ?: 0L,
                    mimeType = context.contentResolver.getType(uri).orEmpty()
                )
            } catch (_: VaultFileTooLargeException) {
                // Файл без известного размера: лимит поймали при чтении.
                _importError.value = VaultError.FileTooLarge
            } catch (e: Exception) {
                // Сообщение исключения — техническое (и не переводится), поэтому
                // экран показывает общий текст, а детали уходят в лог.
                Log.w(TAG, "Импорт файла в сейф не удался", e)
                _importError.value = VaultError.ImportFailure(null)
            }
        }
    }

    fun deleteItem(item: VaultItem) {
        viewModelScope.launch {
            repository.deleteItem(item)
        }
    }

    /**
     * Размер файла по `OpenableColumns.SIZE`.
     *
     * Раньше здесь вызывался `InputStream.available()`, который возвращает лишь
     * количество байт, доступных без блокировки (часто 0), поэтому лимит в 50 МБ
     * никогда не срабатывал. Если провайдер не сообщает размер — возвращаем
     * `null` и полагаемся на проверку при чтении в репозитории.
     */
    private suspend fun getFileSize(context: Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }
}
