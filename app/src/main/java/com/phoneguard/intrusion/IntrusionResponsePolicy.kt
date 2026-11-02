package com.phoneguard.intrusion

/**
 * Какие действия реакции на вторжение выполняются всегда, а какие — только при
 * включённом автоответе.
 *
 * Здесь была ошибка: в `AndroidIntrusionResponseExecutor.execute()` стояло
 * `if (!isAutoIntrusionResponseEnabled) return` **перед всем циклом**, поэтому при
 * выключенном автоответе пользователь не видел ни одного предупреждения, а событие
 * не выгружалось в журнал: пропадала не «агрессивная реакция», а сама фиксация
 * вторжения. Теперь информационные действия ([Category.WARNING], [Category.LOG_EXPORT])
 * безусловны, а активные (блокировка, тревога, фото, SMS, приманки, сейф) — под
 * настройкой; правило вынесено в чистую функцию, чтобы регресс ловился тестом.
 */
object IntrusionResponsePolicy {

    enum class Category {
        /** Показать предупреждение пользователю. */
        WARNING,

        /** Выгрузить событие в файл/cacheDir. */
        LOG_EXPORT,

        /** Запереть сейф PhoneGuard. */
        LOCK_VAULT,

        /** Добавить правило блокировки пакета. */
        BLOCK_PACKAGE,

        /** Заблокировать сеть приложения. */
        BLOCK_NETWORK,

        /** Включить сирену. */
        ALARM,

        /** Скрытая съёмка. */
        PHOTO,

        /** SMS доверенным контактам. */
        NOTIFY_CONTACTS,

        /** Создать файлы-приманки. */
        HONEYPOT
    }

    /** Информационные действия: работают независимо от «автоответа». */
    val ALWAYS_ON: Set<Category> = setOf(Category.WARNING, Category.LOG_EXPORT)

    fun isInformational(category: Category): Boolean = category in ALWAYS_ON

    /**
     * Нужно ли выполнять действие.
     *
     * @param autoResponseEnabled настройка «автоматическая реакция на вторжение»
     */
    fun shouldExecute(category: Category, autoResponseEnabled: Boolean): Boolean =
        isInformational(category) || autoResponseEnabled
}
