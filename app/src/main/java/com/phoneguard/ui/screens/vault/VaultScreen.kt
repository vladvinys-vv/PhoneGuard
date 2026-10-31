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
