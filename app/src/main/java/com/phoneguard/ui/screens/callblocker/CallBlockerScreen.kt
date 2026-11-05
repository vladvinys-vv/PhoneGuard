package com.phoneguard.ui.screens.callblocker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.callblocker.CallBlockDecision
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
    val snackbarHostState = remember { SnackbarHostState() }
    val error by viewModel.error.collectAsState()
    val invalidNumberText = stringResource(R.string.phone_number_invalid)

    // Раньше ошибка «некорректный номер» никак не показывалась: строка просто
    // не добавлялась в список, и пользователь не понимал, почему.
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(invalidNumberText)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.call_sms_blocker)) },
            )
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

            // Жалоба в Bundesnetzagentur: логика была, а дойти до неё из приложения
            // было нельзя. Период фиксирован (90 дней) — этого достаточно для формы
            // BNetzA, а лишние настройки здесь только мешают.
            BnetzaComplaintSection(viewModel = viewModel, snackbarHostState = snackbarHostState)

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

/**
 * Жалоба на мусорные звонки в Bundesnetzagentur (§ 67 TKG, § 7 UWG).
 *
 * Приложение не отправляет письмо само: в жалобе данные пользователя, поэтому оно только
 * готовит два файла и показывает путь. Отправить — решение пользователя.
 */
@Composable
fun BnetzaComplaintSection(
    viewModel: CallBlockerViewModel,
    snackbarHostState: SnackbarHostState
) {
    val result by viewModel.bnetzaResult.collectAsState()
    // Подстановка аргументов — здесь, в композиции: строка с двумя плейсхолдерами не должна
    // форматироваться вручную, иначе легко забыть аргумент и показать «%1$s» пользователю.
    val savedMessage = when (val current = result) {
        is CallBlockerViewModel.BnetzaResult.Ready ->
            stringResource(R.string.bnetza_complaint_saved, current.path, current.incidents)
        else -> null
    }
    val noIncidentsText = stringResource(R.string.bnetza_complaint_no_incidents)
    val noContactText = stringResource(R.string.bnetza_complaint_no_contact)
    val failedText = stringResource(R.string.bnetza_complaint_failed)

    LaunchedEffect(result) {
        when (result) {
            is CallBlockerViewModel.BnetzaResult.Ready -> {
                snackbarHostState.showSnackbar(savedMessage.orEmpty())
                viewModel.consumeBnetzaResult()
            }
            CallBlockerViewModel.BnetzaResult.NoIncidents -> {
                snackbarHostState.showSnackbar(noIncidentsText)
                viewModel.consumeBnetzaResult()
            }
            CallBlockerViewModel.BnetzaResult.NoContact -> {
                snackbarHostState.showSnackbar(noContactText)
                viewModel.consumeBnetzaResult()
            }
            CallBlockerViewModel.BnetzaResult.Failed -> {
                snackbarHostState.showSnackbar(failedText)
                viewModel.consumeBnetzaResult()
            }
            null -> Unit
        }
    }

    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.bnetza_complaint_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.bnetza_complaint_description),
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { viewModel.createBnetzaComplaint() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.bnetza_complaint_create))
            }
        }
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
            Spacer(modifier = Modifier.height(8.dp))
            // Честное объяснение: отменить доставку SMS может только приложение
            // по умолчанию, иначе сообщение остаётся в шторке, но попадает в журнал.
            Text(
                text = stringResource(R.string.sms_blocking_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isWhitelist) Icons.Default.Phone else Icons.Default.Block,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(numbers) { number ->
            var showDeleteDialog by remember { mutableStateOf(false) }
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(stringResource(R.string.delete_number_title)) },
                    text = { Text(stringResource(R.string.delete_irreversible)) },
                    confirmButton = {
                        TextButton(onClick = {
                            onDelete(number)
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
                headlineContent = { Text(number.phoneNumber) },
                supportingContent = number.name?.let { { Text(it) } },
                trailingContent = {
                    IconButton(onClick = { showDeleteDialog = true }, contentDescription = stringResource(R.string.delete_number)) {
                        Icon(Icons.Default.Delete, contentDescription = null)
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    // Причина раньше вычислялась и терялась: пользователь видел только номер
                    // и время. Без неё нельзя ни понять решение, ни возразить на него.
                    val reason = blockReasonLabel(log.reason)
                    Text(if (reason == null) "$type • $time" else "$type • $time • $reason")
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

/**
 * Человеческая подпись причины блокировки.
 *
 * Неизвестное значение (запись из будущей версии, `null` до схемы 9) не показываем:
 * строка `SPAM_DATABASE` в интерфейсе хуже, чем её отсутствие.
 */
@Composable
private fun blockReasonLabel(reason: String?): String? = when (reason) {
    CallBlockDecision.BlockReason.BLACKLISTED.name -> stringResource(R.string.log_reason_blacklisted)
    CallBlockDecision.BlockReason.SPAM_DATABASE.name -> stringResource(R.string.log_reason_spam_database)
    CallBlockDecision.BlockReason.HIDDEN_NUMBER.name -> stringResource(R.string.log_reason_hidden)
    CallBlockDecision.BlockReason.UNPARSEABLE_NUMBER.name ->
        stringResource(R.string.log_reason_unparseable)
    CallBlockDecision.BlockReason.INTERNATIONAL.name ->
        stringResource(R.string.log_reason_international)
    else -> null
}
