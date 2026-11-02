package com.phoneguard.antitheft

/**
 * Решение «что делать с входящим SMS» до выполнения команды.
 *
 * Раньше порядок (`разбор → проверка PIN → действие`) был размазан по
 * `SmsCommandReceiver.processCommand()` вместе с `Context`, `DevicePolicyManager` и
 * отправкой SMS, поэтому проверить его можно было только на устройстве. Ошибка здесь
 * стоит дорого: без PIN удалённая команда `WIPE` не должна выполняться вообще, а
 * попытка подбора PIN должна оставаться видимой владельцу.
 *
 * Зафиксированные правила:
 *  - текста без команды — игнорируем (даже не считаем попыткой);
 *  - неверный PIN — уведомляем владельца **и для неизвестной команды тоже** (иначе
 *    подбор PIN по «мусорным» командам остался бы незамеченным);
 *  - верный PIN + неизвестная команда — ничего не выполняем;
 *  - верный PIN + известная команда — выполняем.
 */
object SmsCommandGuard {

    enum class Decision {
        /** Команды в тексте нет. */
        IGNORE,

        /** PIN неверный: показать локальное уведомление, ничего не выполнять. */
        REJECT_WRONG_PIN,

        /** PIN верный, но команда не распознана. */
        NOTHING_TO_DO,

        /** Можно выполнять действие. */
        EXECUTE
    }

    fun decide(commandParsed: Boolean, pinValid: Boolean, actionKnown: Boolean): Decision = when {
        !commandParsed -> Decision.IGNORE
        !pinValid -> Decision.REJECT_WRONG_PIN
        !actionKnown -> Decision.NOTHING_TO_DO
        else -> Decision.EXECUTE
    }
}
