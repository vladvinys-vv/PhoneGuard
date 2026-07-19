package com.phoneguard.ui.screens.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.VaultItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel = hiltViewModel()
) {
    val isUnlocked by viewModel.isUnlocked.collectAsState()
    val items by viewModel.allItems.collectAsState()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importFile(it, deleteOriginal = false)
        }
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
                                    executor = ContextCompat.getMainExecutor(context)
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.vault_unlock))
                    }
                }
            }
        } else {
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Хранилище пусто")
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
            title = { Text("Удалить файл?") },
            text = { Text("Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(item)
                    showDeleteDialog = false
                }) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
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
                }"
            )
        },
        trailingContent = {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить")
            }
        }
    )
}
