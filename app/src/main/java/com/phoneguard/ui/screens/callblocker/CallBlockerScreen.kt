package com.phoneguard.ui.screens.callblocker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.data.local.BlockedNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallBlockerScreen(
    viewModel: CallBlockerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.blacklist),
        stringResource(R.string.whitelist),
        stringResource(R.string.blocked_log)
    )
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.call_sms_blocker)) })
        },
        floatingActionButton = {
            if (selectedTab < 2) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_number))
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            BlockRulesSection(viewModel = viewModel)

            when (selectedTab) {
                0 -> NumberListSection(
                    numbers = viewModel.blacklist.collectAsState().value,
                    isWhitelist = false,
                    onDelete = { viewModel.deleteNumber(it) },
                    emptyText = stringResource(R.string.empty_blacklist)
                )
                1 -> NumberListSection(
                    numbers = viewModel.whitelist.collectAsState().value,
                    isWhitelist = true,
                    onDelete = { viewModel.deleteNumber(it) },
                    emptyText = stringResource(R.string.empty_whitelist)
                )
                2 -> LogListSection(
                    logs = viewModel.blockedLog.collectAsState().value,
                    formatTimestamp = { viewModel.formatTimestamp(it) },
                    emptyText = stringResource(R.string.empty_log)
                )
            }
        }
    }

    if (showAddDialog) {
        AddNumberDialog(
            isWhitelist = selectedTab == 1,
            onDismiss = { showAddDialog = false },
            onConfirm = { number, name ->
                viewModel.addNumber(number, name, selectedTab == 1)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun BlockRulesSection(viewModel: CallBlockerViewModel) {
    Card(modifier = Modifier.padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.block_rules), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = viewModel.blockUnknown.collectAsState().value,
                    onCheckedChange = { viewModel.toggleBlockUnknown(it) }
                )
                Text(stringResource(R.string.block_unknown))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = viewModel.blockHidden.collectAsState().value,
                    onCheckedChange = { viewModel.toggleBlockHidden(it) }
                )
                Text(stringResource(R.string.block_hidden))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = viewModel.blockInternational.collectAsState().value,
                    onCheckedChange = { viewModel.toggleBlockInternational(it) }
                )
                Text(stringResource(R.string.block_international))
            }
        }
    }
}

@Composable
fun NumberListSection(
    numbers: List<BlockedNumber>,
    isWhitelist: Boolean,
    onDelete: (BlockedNumber) -> Unit,
    emptyText: String
) {
    if (numbers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(numbers) { number ->
            ListItem(
                headlineContent = { Text(number.phoneNumber) },
                supportingContent = number.name?.let { { Text(it) } },
                trailingContent = {
                    IconButton(onClick = { onDelete(number) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_number))
                    }
                }
            )
            Divider()
        }
    }
}

@Composable
fun LogListSection(
    logs: List<com.phoneguard.data.local.BlockedLog>,
    formatTimestamp: (Long) -> String,
    emptyText: String
) {
    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(logs) { log ->
            ListItem(
                headlineContent = { Text(log.phoneNumber) },
                supportingContent = {
                    val type = if (log.isSms) stringResource(R.string.blocked_sms) else stringResource(R.string.blocked_call)
                    val time = formatTimestamp(log.timestamp)
                    Text("$type • $time")
                }
            )
            Divider()
        }
    }
}

@Composable
fun AddNumberDialog(
    isWhitelist: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    var number by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_number)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text(stringResource(R.string.phone_number)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(number, name.ifEmpty { null }) },
                enabled = number.isNotEmpty()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
