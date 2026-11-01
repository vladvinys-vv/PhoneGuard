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

package kotlinx.coroutines

typealias CancellationException = java.util.concurrent.CancellationException

interface CoroutineContext {
    operator fun plus(context: CoroutineContext): CoroutineContext
}

open class CoroutineDispatcher : CoroutineContext {
    override fun plus(context: CoroutineContext): CoroutineContext = this
}

object Dispatchers {
    val Default: CoroutineDispatcher = CoroutineDispatcher()
    val Main: CoroutineDispatcher = CoroutineDispatcher()
    val IO: CoroutineDispatcher = CoroutineDispatcher()
    val Unconfined: CoroutineDispatcher = CoroutineDispatcher()
}

interface Job {
    val isActive: Boolean
    fun cancel(cause: CancellationException? = null)
    suspend fun join()
}

interface CompletableJob : Job {
    fun complete(): Boolean
}

private class FakeJob : CompletableJob {
    override val isActive: Boolean get() = true
    override fun cancel(cause: CancellationException?) = Unit
    override suspend fun join() = Unit
    override fun complete(): Boolean = true
}

fun Job(parent: Job? = null): CompletableJob = FakeJob()

fun SupervisorJob(parent: Job? = null): CompletableJob = FakeJob()

interface Deferred<T> : Job {
    suspend fun await(): T
}

interface CoroutineScope {
    val coroutineContext: CoroutineContext
    val isActive: Boolean get() = true
}

private class FakeScope(override val coroutineContext: CoroutineContext) : CoroutineScope

fun CoroutineScope(context: CoroutineContext): CoroutineScope = FakeScope(context)

class CompletableDeferred<T> : Deferred<T> {
    fun complete(value: T): Boolean = true
    override val isActive: Boolean get() = true
    override fun cancel(cause: CancellationException?) = Unit
    override suspend fun join() = Unit
    override suspend fun await(): T = TODO()
}

interface CancellableContinuation<T> {
    val isActive: Boolean
    fun resume(value: T)
    fun resumeWith(result: Result<T>)
    fun invokeOnCancellation(handler: (Throwable?) -> Unit)
}

fun MainScope(): CoroutineScope = TODO()

fun CoroutineScope.cancel(cause: CancellationException? = null) = Unit

fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T = TODO()

fun CoroutineScope.launch(
    context: CoroutineContext = Dispatchers.Default,
    block: suspend CoroutineScope.() -> Unit
): Job = TODO()

fun <T> CoroutineScope.async(
    context: CoroutineContext = Dispatchers.Default,
    block: suspend CoroutineScope.() -> T
): Deferred<T> = TODO()

suspend fun <T> withContext(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T
): T = TODO()

suspend fun delay(timeMillis: Long) = Unit

suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend CoroutineScope.() -> T): T? = TODO()

suspend fun <T> coroutineScope(block: suspend CoroutineScope.() -> T): T = TODO()

suspend fun <T> supervisorScope(block: suspend CoroutineScope.() -> T): T = TODO()

suspend fun <T> suspendCancellableCoroutine(block: (CancellableContinuation<T>) -> Unit): T = TODO()

suspend fun yield() = Unit

// kotlinx.coroutines.test — нужен, чтобы тесты на ViewModel-логику тоже проверялись.
