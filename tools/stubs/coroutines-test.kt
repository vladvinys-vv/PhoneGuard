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

package kotlinx.coroutines.test

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

open class TestDispatcher : CoroutineDispatcher()

fun UnconfinedTestDispatcher(): TestDispatcher = TestDispatcher()

fun StandardTestDispatcher(): TestDispatcher = TestDispatcher()

object Dispatchers {
    var Main: TestDispatcher = TestDispatcher()
}

fun runTest(block: suspend CoroutineScope.() -> Unit) = Unit

fun setMain(dispatcher: TestDispatcher) = Unit

fun resetMain() = Unit

fun <T> TestScope(block: suspend CoroutineScope.() -> T): T = TODO()

fun advanceUntilIdle(): Unit = Unit

// kotlinx.coroutines.channels
