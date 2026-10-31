package com.phoneguard.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Одноразовые события UI (снекбар, переход, тост).
 *
 * Раньше события шли через `MutableSharedFlow(extraBufferCapacity = 1)`: событие,
 * отправленное до появления подписчика (сразу после `onCreate`/поворота экрана),
 * терялось, а при отсутствии коллектора `emit` из корутины зависал на заполненном
 * буфере. `Channel` буферизует события и доставляет их ровно один раз.
 */
abstract class EventViewModel : ViewModel() {

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** Отправляет событие; не блокирует вызывающий поток. */
    fun sendEvent(event: Event) {
        eventChannel.trySend(event)
    }

    /**
     * Подписка на события внутри Compose.
     *
     * Мёртвый наблюдатель жизненного цикла удалён: он добавлялся в `LaunchedEffect`,
     * обрабатывал `ON_STOP` пустым блоком и никогда не снимался с владельца.
     */
    @Composable
    fun ObserveEvents(onEvent: (Event) -> Unit) {
        LaunchedEffect(this) {
            events.collect { onEvent(it) }
        }
    }
}

sealed interface Event {
    /** Текст берётся из ресурсов на стороне UI — ViewModel не хранит локализованных строк. */
    data class ShowSnackbar(@StringRes val messageRes: Int, val actionLabel: String? = null) : Event
    data class Navigate(val route: String, val popUpTo: String? = null) : Event
    data class ShowToast(val message: String) : Event
    data class ShowDialog(val title: String, val message: String, val onConfirm: () -> Unit) : Event
}
