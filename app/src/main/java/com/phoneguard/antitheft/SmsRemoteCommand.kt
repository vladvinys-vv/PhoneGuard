package com.phoneguard.antitheft

import java.util.Locale

/**
 * Разбор удалённой SMS-команды `#PG:PIN:COMMAND`.
 *
 * Вынесено из [SmsCommandReceiver] без Android-зависимостей: от разбора зависит, выполнится ли
 * удалённая блокировка, тревога или **стирание данных**. Ошибка в разборе — это либо
 * неработающая защита (команда не распознана), либо ложное срабатывание на обычном тексте.
 *
 * Формат: `#PG:`, PIN из цифр, `:`, команда из букв/цифр/подчёркиваний. Регистр не важен —
 * приводим к верхнему через [Locale.US], иначе в турецкой локали `wipe` превращается в `WİPE`
 * и команда перестаёт совпадать.
 */
internal data class SmsRemoteCommand(
    val pin: String,
    val action: Action?
) {

    /** Поддерживаемые действия. Неизвестная команда разбирается (`action == null`), но не выполняется. */
    enum class Action {
        LOCK, ALARM, LOCATION, WIPE, PHOTO, STATUS;

        companion object {
            fun from(raw: String): Action? {
                val normalized = raw.uppercase(Locale.US)
                return entries.firstOrNull { it.name == normalized }
            }
        }
    }

    companion object {
        private val PATTERN = Regex("""#PG:(\d+):(\w+)""", RegexOption.IGNORE_CASE)

        /** Есть ли в тексте хоть одна команда — дешёвая проверка до `goAsync()`. */
        fun containsCommand(body: String): Boolean = PATTERN.containsMatchIn(body)

        /**
         * Разбирает первую команду в тексте.
         *
         * `null` — команды нет. Неизвестное действие возвращается как [Action] = null, чтобы
         * вызывающий код успел проверить PIN и показать уведомление о попытке подбора.
         */
        fun parse(body: String): SmsRemoteCommand? {
            val match = PATTERN.find(body) ?: return null
            val pin = match.groupValues[1]
            if (pin.isEmpty()) return null
            return SmsRemoteCommand(pin = pin, action = Action.from(match.groupValues[2]))
        }
    }
}
