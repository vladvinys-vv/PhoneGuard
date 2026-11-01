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

package kotlinx.coroutines.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

fun interface FlowCollector<in T> {
    suspend fun emit(value: T)
}

interface Flow<out T> {
    suspend fun collect(collector: FlowCollector<T>)
}

interface SharedFlow<out T> : Flow<T> {
    val replayCache: List<T>
}

interface MutableSharedFlow<T> : SharedFlow<T> {
    suspend fun emit(value: T)
    fun tryEmit(value: T): Boolean
    val subscriptionCount: Int
}

interface StateFlow<out T> : SharedFlow<T> {
    val value: T
}

interface MutableStateFlow<T> : StateFlow<T>, MutableSharedFlow<T> {
    override var value: T
    fun compareAndSet(expect: T, update: T): Boolean
}

fun <T> MutableStateFlow(value: T): MutableStateFlow<T> = TODO()

fun <T> MutableSharedFlow(
    replay: Int = 0,
    extraBufferCapacity: Int = 0,
    onBufferOverflow: Int = 0
): MutableSharedFlow<T> = TODO()

fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = TODO()

fun <T> MutableSharedFlow<T>.asSharedFlow(): SharedFlow<T> = TODO()

fun <T> Channel<T>.receiveAsFlow(): Flow<T> = TODO()

fun <T> Channel<T>.consumeAsFlow(): Flow<T> = TODO()

abstract class SharingStarted {
    companion object {
        val Eagerly: SharingStarted = object : SharingStarted() {}
        val Lazily: SharingStarted = object : SharingStarted() {}
        fun WhileSubscribed(
            stopTimeoutMillis: Long = 0L,
            replayExpirationMillis: Long = Long.MAX_VALUE
        ): SharingStarted = object : SharingStarted() {}
    }
}

fun <T> MutableStateFlow<T>.update(function: (T) -> T) = Unit

fun <T> MutableStateFlow<T>.getAndUpdate(function: (T) -> T): T = TODO()

suspend fun <T> Flow<T>.first(): T = TODO()

suspend fun <T> Flow<T>.first(predicate: suspend (T) -> Boolean): T = TODO()

suspend fun <T> Flow<T>.firstOrNull(): T? = TODO()

suspend fun <T> Flow<T>.firstOrNull(predicate: suspend (T) -> Boolean): T? = TODO()

suspend fun <T> Flow<T>.last(): T = TODO()

suspend fun <T> Flow<T>.lastOrNull(): T? = TODO()

suspend fun <T> Flow<T>.single(): T = TODO()

suspend fun <T> Flow<T>.singleOrNull(): T? = TODO()

suspend fun <T> Flow<T>.toList(): List<T> = TODO()

suspend fun <T> Flow<T>.count(): Int = TODO()

suspend fun <T, R> Flow<T>.fold(initial: R, operation: suspend (acc: R, value: T) -> R): R = TODO()

suspend fun <T> Flow<T>.reduce(operation: suspend (acc: T, value: T) -> T): T = TODO()

suspend fun <T> Flow<T>.any(predicate: suspend (T) -> Boolean): Boolean = TODO()

suspend fun <T> Flow<T>.all(predicate: suspend (T) -> Boolean): Boolean = TODO()

suspend fun <T> Flow<T>.none(predicate: suspend (T) -> Boolean): Boolean = TODO()

suspend fun <T> Flow<T>.collect(action: suspend (T) -> Unit) = Unit

suspend fun <T> Flow<T>.collectLatest(action: suspend (T) -> Unit) = Unit

fun <T> Flow<T>.launchIn(scope: CoroutineScope): Job = TODO()

fun <T> Flow<T>.onEach(action: suspend (T) -> Unit): Flow<T> = TODO()

fun <T> Flow<T>.onStart(action: suspend FlowCollector<T>.() -> Unit): Flow<T> = TODO()

fun <T> Flow<T>.onCompletion(action: suspend (Throwable?) -> Unit): Flow<T> = TODO()

fun <T> Flow<T>.catch(action: suspend (Throwable) -> Unit): Flow<T> = TODO()

fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> = TODO()

fun <T, R> Flow<T>.mapNotNull(transform: suspend (T) -> R?): Flow<R> = TODO()

fun <T> Flow<T>.filter(predicate: suspend (T) -> Boolean): Flow<T> = TODO()

fun <T> Flow<T>.filterNotNull(): Flow<T> = TODO()

fun <T, R> Flow<T>.transform(transform: suspend FlowCollector<R>.(T) -> Unit): Flow<R> = TODO()

fun <T> Flow<T>.distinctUntilChanged(): Flow<T> = TODO()

fun <T> Flow<T>.debounce(timeoutMillis: Long): Flow<T> = TODO()

fun <T> Flow<T>.take(count: Int): Flow<T> = TODO()

fun <T> Flow<T>.drop(count: Int): Flow<T> = TODO()

fun <T, R> Flow<T>.flatMapLatest(transform: suspend (T) -> Flow<R>): Flow<R> = TODO()

fun <T, R> Flow<T>.flatMapConcat(transform: suspend (T) -> Flow<R>): Flow<R> = TODO()

fun <T> Flow<T>.stateIn(scope: CoroutineScope, started: SharingStarted, initialValue: T): StateFlow<T> = TODO()

fun <T> Flow<T>.shareIn(scope: CoroutineScope, started: SharingStarted, replay: Int = 0): SharedFlow<T> = TODO()

fun <T> flow(block: suspend FlowCollector<T>.() -> Unit): Flow<T> = TODO()

fun <T> flowOf(vararg elements: T): Flow<T> = TODO()

fun <T> emptyFlow(): Flow<T> = TODO()

fun <T> flowOf(element: T): Flow<T> = TODO()

fun <T1, T2, R> combine(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    transform: suspend (T1, T2) -> R
): Flow<R> = TODO()

fun <T1, T2, T3, R> combine(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    transform: suspend (T1, T2, T3) -> R
): Flow<R> = TODO()

fun <T1, T2, T3, T4, R> combine(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    transform: suspend (T1, T2, T3, T4) -> R
): Flow<R> = TODO()

fun <T1, T2, T3, T4, T5, R> combine(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    transform: suspend (T1, T2, T3, T4, T5) -> R
): Flow<R> = TODO()

fun <T, R> combine(
    flows: Iterable<Flow<T>>,
    transform: suspend (Array<T>) -> R
): Flow<R> = TODO()

fun <T> combine(vararg flows: Flow<T>): Flow<Array<T>> = TODO()

fun <T> merge(vararg flows: Flow<T>): Flow<T> = TODO()

fun <T> Flow<T>.conflate(): Flow<T> = TODO()

fun <T> Flow<T>.buffer(capacity: Int = 0): Flow<T> = TODO()

fun <T> Flow<T>.flowOn(context: kotlinx.coroutines.CoroutineContext): Flow<T> = TODO()

interface ProducerScope<in E> : FlowCollector<E>, kotlinx.coroutines.CoroutineScope {
    val channel: kotlinx.coroutines.channels.SendChannel<E>
}

fun <T> callbackFlow(block: suspend ProducerScope<T>.() -> Unit): Flow<T> = TODO()
