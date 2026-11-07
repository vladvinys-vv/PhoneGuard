package com.phoneguard.ui.screens.simswap

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.phoneguard.R
import com.phoneguard.model.SimSwapEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimSwapHistoryScreen(
    onBack: () -> Unit,
    viewModel: SimSwapViewModel = hiltViewModel()
) {
    val history by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sim_swap_history),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(onClick = {
                // Раньше CSV писался прямо здесь, на главном потоке: `FileWriter` в
                // `onClick` — это блокировка UI на всё время записи внешнего хранилища
                // (вплоть до ANR на большой истории). Теперь запись в IO, а Toast — обратно
                // на главный поток.
                scope.launch {
                    // Раньше сообщение об успехе показывалось всегда, даже если файл
                    // не записался (например, внешнее хранилище отмонтировано).
                    val path = withContext(Dispatchers.IO) { exportCsv(context, history.history) }
                    val message = if (path != null) {
                        context.getString(R.string.export_logs_saved, path)
                    } else {
                        context.getString(R.string.export_logs_failed)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }) {
                Icon(imageVector = Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(R.string.sim_swap_export))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history.history, key = { event -> event.id }) { event ->
                SimSwapEventCard(event = event)
            }
        }
    }
}

@Composable
private fun SimSwapEventCard(event: SimSwapEvent) {
    val status = when {
        event.isAttackSuspected -> stringResource(R.string.sim_swap_suspected_attack)
        !event.isConfirmed -> stringResource(R.string.sim_swap_unconfirmed)
        else -> stringResource(R.string.sim_swap_confirmed)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (event.isAttackSuspected) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = status, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(
                    R.string.sim_swap_imsi_change,
                    event.oldImsi ?: "?",
                    event.newImsi ?: "?"
                )
            )
            Text(text = event.addressString ?: stringResource(R.string.sim_swap_unknown))
        }
    }
}

/**
 * Пишет историю подмен SIM в CSV рядом с приложением.
 *
 * `suspend`, а не просто функция: запись файла обязана идти с IO-диспетчера. Так это
 * проверяет компилятор — вызвать её случайно из `onClick` уже не получится.
 *
 * @return путь к файлу либо `null`, если записать не удалось
 */
private suspend fun exportCsv(
    context: android.content.Context,
    history: List<SimSwapEvent>
): String? {
    val externalDir = context.getExternalFilesDir(null) ?: return null
    val file = File(externalDir, "sim_swap_history.csv")
    return runCatching {
        FileWriter(file).use { writer ->
            writer.appendLine("id,timestamp,old_imsi,new_imsi,lat,lng,address,confirmed,attack_suspected")
            history.forEach { event ->
                // Кавычки в адресе удваиваются — иначе CSV ломается на запятой/кавычке.
                val address = (event.addressString ?: "").replace("\"", "\"\"")
                writer.appendLine(
                    listOf(
                        event.id.toString(),
                        event.timestamp.toString(),
                        event.oldImsi.orEmpty(),
                        event.newImsi.orEmpty(),
                        event.latitude?.toString().orEmpty(),
                        event.longitude?.toString().orEmpty(),
                        "\"$address\"",
                        event.isConfirmed.toString(),
                        event.isAttackSuspected.toString()
                    ).joinToString(",")
                )
            }
        }
        file.absolutePath
    }.getOrNull()
}