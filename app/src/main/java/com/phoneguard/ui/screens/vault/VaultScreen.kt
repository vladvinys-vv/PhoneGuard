package com.phoneguard.ui.screens.vault

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.VaultItem
import com.phoneguard.model.VaultItemCategory
import com.phoneguard.util.IntentUtils
import com.phoneguard.vault.VaultArchiveFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel = hiltViewModel()
) {
    val isUnlocked by viewModel.isUnlocked.collectAsState()
    val items by viewModel.allItems.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val previewError by viewModel.previewError.collectAsState()
    val previewIntent by viewModel.previewIntent.collectAsState()
    val transferEvent by viewModel.transferEvent.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(isUnlocked) {
        val window = (view.context as? android.app.Activity)?.window
        if (isUnlocked) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(previewIntent) {
        previewIntent?.let { intent ->
            IntentUtils.open(context, intent, R.string.vault_preview_failed)
            viewModel.consumePreview()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importFile(it, deleteOriginal = false)
        }
    }

    // Импорт сейфа: сначала выбираем файл архива, потом спрашиваем парольную фразу.
    val vaultPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) pendingImportUri = uri
    }

    // Автоблокировка: раньше сейф оставался разблокированным, если приложение
    // уходило в фон с открытым экраном сейфа (переключение задач, блокировка экрана).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.lock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showExportDialog) {
        VaultPassphraseDialog(
            title = stringResource(R.string.vault_export_vault),
            confirmText = stringResource(R.string.vault_export_vault),
            requireRepeat = true,
            onDismiss = { showExportDialog = false },
            onConfirm = { passphrase ->
                showExportDialog = false
                viewModel.exportVault(passphrase)
            }
        )
    }

    pendingImportUri?.let { uri ->
        VaultPassphraseDialog(
            title = stringResource(R.string.vault_import_vault),
            confirmText = stringResource(R.string.vault_import_vault),
            requireRepeat = false,
            onDismiss = { pendingImportUri = null },
            onConfirm = { passphrase ->
                pendingImportUri = null
                viewModel.importVault(uri, passphrase)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.secure_vault)) }
            )
        },
        floatingActionButton = {
            if (isUnlocked) {
                FloatingActionButton(onClick = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Default.Add, stringResource(R.string.vault_import))
                }
            }
        }
    ) { padding ->
        if (!isUnlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.vault_unlock),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Button(
                        onClick = {
                            if (context is FragmentActivity) {
                                viewModel.authenticateUser(
                                    activity = context,
                                    executor = ContextCompat.getMainExecutor(context),
                                    promptTitle = context.getString(R.string.vault_unlock),
                                    promptSubtitle = context.getString(R.string.vault_unlock_subtitle),
                                    promptNegative = context.getString(R.string.cancel)
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.vault_unlock))
                    }
                }
            }
        } else {
            Column(modifier = Modifier.padding(padding)) {
                VaultTransferSection(
                    onExport = { showExportDialog = true },
                    onImport = { vaultPickerLauncher.launch(arrayOf("*/*")) }
                )
                transferEvent?.let { event ->
                    Text(
                        text = vaultTransferMessage(event),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    TextButton(
                        onClick = { viewModel.consumeTransferEvent() },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
            authError?.let { error ->
                Text(
                    text = vaultErrorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            importError?.let { error ->
                Text(
                    text = vaultErrorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            previewError?.let { error ->
                Text(
                    text = vaultErrorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.vault_empty))
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                ) {
                    items(items) { item ->
                        VaultItemCard(item = item, onDelete = { viewModel.deleteItem(it) })
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun VaultItemCard(item: VaultItem, onDelete: (VaultItem) -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val sdf = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dateStr = remember(item.createdAt) { sdf.format(Date(item.createdAt)) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_file_title)) },
            text = { Text(stringResource(R.string.delete_irreversible)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(item)
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    ListItem(
        headlineContent = { Text(item.fileName) },
        supportingContent = {
            Text(
                "$dateStr • ${
                    android.text.format.Formatter.formatFileSize(
                        androidx.compose.ui.platform.LocalContext.current,
                        item.fileSize
                    )
                } • ${item.category.name}"
            )
        },
        leadingContent = {
            Icon(
                imageVector = when (item.category) {
                    VaultItemCategory.IMAGE -> Icons.Default.Image
                    VaultItemCategory.VIDEO -> Icons.Default.PlayArrow
                    VaultItemCategory.DOCUMENT -> Icons.Default.Description
                    VaultItemCategory.OTHER -> Icons.Default.InsertDriveFile
                },
                contentDescription = item.category.name
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        viewModel.clearPreviewError()
                        viewModel.previewItem(item)
                    },
                    contentDescription = stringResource(R.string.preview)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null)
                }
                IconButton(onClick = { showDeleteDialog = true }, contentDescription = stringResource(R.string.delete)) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                }
            }
        }
    )
}

/** Текст ошибки хранилища — локализуется на уровне UI. */
@Composable
private fun vaultErrorMessage(error: VaultError): String = when (error) {
    VaultError.AuthenticationFailed -> stringResource(R.string.vault_auth_failed)
    VaultError.BiometricUnavailable -> stringResource(R.string.vault_biometric_unavailable)
    VaultError.AuthenticationNotPossible -> stringResource(R.string.vault_auth_not_possible)
    VaultError.FileTooLarge ->
        stringResource(R.string.vault_file_too_large, VaultViewModel.MAX_FILE_SIZE_MB.toInt())
    is VaultError.PlatformFailure -> error.text.orEmpty()
    is VaultError.ImportFailure ->
        stringResource(R.string.vault_import_failed, error.text ?: stringResource(R.string.error_generic))
    VaultError.PreviewFailure -> stringResource(R.string.vault_preview_failed)
}

/**
 * Раздел «перенос сейфа» над списком файлов.
 *
 * Кнопки появляются только в разблокированном сейфе (весь экран уже под `FLAG_SECURE`):
 * выгрузка расшифровывает все файлы, поэтому до биометрии её показывать нельзя.
 */
@Composable
fun VaultTransferSection(onExport: () -> Unit, onImport: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.vault_transfer_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.vault_transfer_description),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport) {
                    Text(stringResource(R.string.vault_export_vault))
                }
                OutlinedButton(onClick = onImport) {
                    Text(stringResource(R.string.vault_import_vault))
                }
            }
        }
    }
}

/**
 * Ввод парольной фразы для архива сейфа.
 *
 * Повтор нужен при создании архива: опечатка в единственном поле сделала бы файл
 * неоткрываемым навсегда, а проверить это можно только на новом телефоне.
 */
@Composable
private fun VaultPassphraseDialog(
    title: String,
    confirmText: String,
    requireRepeat: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val minLength = VaultArchiveFormat.MIN_PASSPHRASE_LENGTH
    val tooShort = passphrase.isNotEmpty() && passphrase.length < minLength
    val mismatch = requireRepeat && repeated.isNotEmpty() && repeated != passphrase
    val canConfirm = passphrase.length >= minLength && (!requireRepeat || repeated == passphrase)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vault_transfer_passphrase_hint, minLength))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.vault_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (requireRepeat) {
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = { repeated = it },
                        label = { Text(stringResource(R.string.vault_passphrase_repeat)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
                if (tooShort) {
                    Text(
                        stringResource(R.string.vault_passphrase_too_short, minLength),
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (mismatch) {
                    Text(
                        stringResource(R.string.vault_passphrase_mismatch),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase.toCharArray()) },
                enabled = canConfirm
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Текст результата переноса — локализуется здесь, ViewModel отдаёт только коды. */
@Composable
private fun vaultTransferMessage(event: VaultViewModel.VaultTransferEvent): String = when (event) {
    is VaultViewModel.VaultTransferEvent.VaultTransferSaved ->
        stringResource(R.string.vault_export_saved, event.path, event.items)
    is VaultViewModel.VaultTransferEvent.VaultTransferRestored ->
        stringResource(R.string.vault_import_done, event.items)
    VaultViewModel.VaultTransferEvent.VaultTransferEmpty ->
        stringResource(R.string.vault_export_empty)
    VaultViewModel.VaultTransferEvent.VaultTransferFailed ->
        stringResource(R.string.vault_transfer_failed)
    is VaultViewModel.VaultTransferEvent.VaultTransferRefused -> when (event.reason) {
        VaultArchiveFormat.Rejection.EMPTY_PASSPHRASE,
        VaultArchiveFormat.Rejection.WEAK_PASSPHRASE ->
            stringResource(
                R.string.vault_passphrase_too_short,
                VaultArchiveFormat.MIN_PASSPHRASE_LENGTH
            )
        VaultArchiveFormat.Rejection.NOT_AN_ARCHIVE ->
            stringResource(R.string.vault_import_not_archive)
        VaultArchiveFormat.Rejection.UNSUPPORTED_VERSION ->
            stringResource(R.string.vault_import_bad_version)
        VaultArchiveFormat.Rejection.WRONG_PASSPHRASE_OR_CORRUPT ->
            stringResource(R.string.vault_import_wrong_passphrase)
        else -> stringResource(R.string.vault_import_corrupt)
    }
}
