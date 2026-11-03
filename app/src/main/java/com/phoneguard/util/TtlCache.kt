package com.phoneguard.util

/**
 * Кэш одного значения с временем жизни.
 *
 * Вынесено из [InstalledAppsCache], где значение и метка времени лежали в **двух отдельных**
 * `@Volatile`-полях: читающий поток мог увидеть новое значение со старой меткой (и наоборот),
 * потому что пара обновлялась не атомарно. Здесь значение и время лежат в одном неизменяемом
 * объекте, который публикуется одной ссылкой — читатель видит либо целиком старое состояние,
 * либо целиком новое.
 *
 * Часы передаются параметром: без этого «истёк ли срок» проверялось бы только ожиданием в тесте.
 */
internal class TtlCache<T>(
    private val validityMs: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private data class Entry<T>(val value: T, val storedAt: Long)

    @Volatile
    private var entry: Entry<T>? = null

    /**
     * Значение, если оно ещё свежее, иначе `null`.
     *
     * Ровно на границе (`now - storedAt == validityMs`) значение считается устаревшим: иначе
     * «24 часа» превращались бы в «24 часа + сколько угодно», если часы идут назад.
     */
    fun getOrNull(): T? {
        val current = entry ?: return null
        val age = clock() - current.storedAt
        return if (age >= 0 && age < validityMs) current.value else null
    }

    /** Кладёт значение и запоминает текущее время (одна атомарная публикация). */
    fun put(value: T) {
        entry = Entry(value, clock())
    }

    /** Сбрасывает кэш: следующее чтение вернёт `null`. */
    fun invalidate() {
        entry = null
    }

    /** Есть ли запись вообще (даже устаревшая) — нужен для диагностики и тестов. */
    fun hasEntry(): Boolean = entry != null
}
