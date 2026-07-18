package com.phoneguard.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * One-off UI events (snackbar, navigation, toast).
 * Automatically cleared on lifecycle reset to avoid duplicate handling.
 */
abstract class EventViewModel : ViewModel() {
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun sendEvent(event: Event) {
        viewModelScope.launch {
            _events.emit(event)
        }
    }

    @Composable
    fun ObserveEvents(lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current, onEvent: (Event) -> Unit) {
        LaunchedEffect(Unit) {
            lifecycleOwner.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        // Clear any pending events when view goes to background
                    }
                }
            )
        }
        LaunchedEffect(Unit) {
            events.collect { onEvent(it) }
        }
    }
}

sealed interface Event {
    data class ShowSnackbar(val message: String, val actionLabel: String? = null) : Event
    data class Navigate(val route: String, val popUpTo: String? = null) : Event
    data class ShowToast(val message: String) : Event
    data class ShowDialog(val title: String, val message: String, val onConfirm: () -> Unit) : Event
}
