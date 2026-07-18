package com.phoneguard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phoneguard.R

@Composable
fun SettingsScreen() {
    PlaceholderScreen(title = stringResource(R.string.settings))
}

@Composable
fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "$title Coming Soon!", style = MaterialTheme.typography.headlineMedium)
    }
}
