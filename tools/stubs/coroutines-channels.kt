// Минимальные заглушки kotlinx.coroutines для офлайн-проверки типов.
//
// Зачем: в песочнице нет Maven Central, поэтому настоящие корутины на classpath
// отсутствуют, и компилятор Kotlin не может разрешить `Flow`, `first()`, `stateIn()`.
// Из-за этого тип `Flow<String?>` превращается в «error type», и настоящие ошибки
// типов (например, `isBlank()` у `String?`) не диагностируются вообще — именно так
// в проект попал код, из-за которого не собирался app-модуль.
//
// Заглушки повторяют сигнатуры публичного API только в той части, которую использует
// проект. Это НЕ реализация: тела пустые, класс собирается отдельно от приложения
// (tools/kotlin_frontend_check.py --coroutines-stub) и подкладывается в classpath.
@file:Suppress("unused", "UNUSED_PARAMETER", "unused_parameter")

package kotlinx.coroutines.channels

import kotlinx.coroutines.CoroutineScope

interface SendChannel<in E> {
    suspend fun send(element: E)
    fun trySend(element: E): Boolean
    fun close(cause: Throwable? = null): Boolean
    val isClosedForSend: Boolean
}

interface ReceiveChannel<out E> {
    suspend fun receive(): E
    fun tryReceive(): E?
    val isClosedForReceive: Boolean
}

interface Channel<E> : SendChannel<E>, ReceiveChannel<E> {
    companion object {
        const val BUFFERED = -2
        const val RENDEZVOUS = 0
        const val CONFLATED = -1
        const val UNLIMITED = Int.MAX_VALUE
    }
}

fun <E> Channel(capacity: Int = Channel.RENDEZVOUS): Channel<E> = TODO()

suspend fun <E> SendChannel<E>.awaitClose(block: () -> Unit) = Unit

// kotlinx.coroutines.flow
