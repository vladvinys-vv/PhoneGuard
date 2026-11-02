// Минимальные заглушки androidx.lifecycle для офлайн-проверки типов.
//
// Зачем: `ViewModel`/`viewModelScope` — из AndroidX, которого нет на classpath песочницы.
// Без них `viewModelScope.launch { … }` не разрешается, поэтому **тела ViewModel-методов
// не проверяются вообще**: ошибки типов внутри корутин молчат (так уже проскочили три
// ошибки сборки — см. tools/stubs/coroutines-core.kt).
//
// Это не реализация: тела пустые, класс собирается отдельно от приложения
// (tools/kotlin_frontend_check.py) и подкладывается в classpath.
@file:Suppress("unused", "UNUSED_PARAMETER", "unused_parameter")

package androidx.lifecycle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope

interface LifecycleObserver

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

class Lifecycle {

    enum class Event {
        ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY
    }

    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED
    }

    val currentState: State get() = State.INITIALIZED

    fun addObserver(observer: LifecycleObserver) = Unit

    fun removeObserver(observer: LifecycleObserver) = Unit
}

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event)
}

interface DefaultLifecycleObserver : LifecycleObserver {
    fun onCreate(owner: LifecycleOwner) = Unit
    fun onStart(owner: LifecycleOwner) = Unit
    fun onResume(owner: LifecycleOwner) = Unit
    fun onPause(owner: LifecycleOwner) = Unit
    fun onStop(owner: LifecycleOwner) = Unit
    fun onDestroy(owner: LifecycleOwner) = Unit
}

val LifecycleOwner.lifecycleScope: CoroutineScope get() = TODO()

abstract class ViewModel {

    protected open fun onCleared() = Unit

    fun clear() = Unit
}

val ViewModel.viewModelScope: CoroutineScope get() = TODO()

open class LifecycleService : Service(), LifecycleOwner {
    override val lifecycle: Lifecycle = Lifecycle()
    override fun onBind(intent: Intent?): IBinder? = null
}

class ViewModelStore {
    fun clear() = Unit
}

class ViewModelProvider(private val owner: Any) {
    constructor(owner: Any, factory: Any) : this(owner)

    fun <T : ViewModel> get(modelClass: Class<T>): T = TODO()
}
