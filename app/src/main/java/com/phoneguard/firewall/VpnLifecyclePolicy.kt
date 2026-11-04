package com.phoneguard.firewall

/**
 * Что делать сервису VPN при очередном `onStartCommand` / ревокации.
 *
 * Логика жила прямо в `PhoneGuardVpnService.onStartCommand` одной `when` по строке действия, и в
 * ней была дыра: сервис стартует как `START_STICKY`, поэтому после убийства процесса система
 * перезапускает его **с пустым интентом**. Ветка `else -> START_NOT_STICKY` не запускала туннель
 * и не завершала сервис — он висел без уведомления, хотя для `startForegroundService` система
 * ждёт `startForeground()` в первые пять секунд (иначе процесс убивают с ANR в журнале).
 *
 * Правила здесь чистые, поэтому обе ветки — «команда есть» и «команды нет» — проверяются тестом.
 */
internal object VpnLifecyclePolicy {

    /** Что пришло в интенте. */
    enum class Command {
        CONNECT,
        DISCONNECT,

        /** Пустой/неизвестный интент: типичный случай — перезапуск системы (`START_STICKY`). */
        UNKNOWN
    }

    /** Что делать сервису. */
    enum class Reaction {
        /** Поднять туннель (сервис остаётся foreground). */
        START_TUNNEL,

        /** Туннель уже есть — только убедиться, что уведомление на месте. */
        KEEP_TUNNEL,

        /** Остановить туннель и завершить сервис. */
        STOP_TUNNEL,

        /** Туннеля нет и команды нет — завершиться, а не висеть без уведомления. */
        STOP_SERVICE
    }

    fun commandOf(action: String?, connectAction: String, disconnectAction: String): Command = when {
        action == connectAction -> Command.CONNECT
        action == disconnectAction -> Command.DISCONNECT
        // `null` — перезапуск системы; незнакомая строка — чужая команда. Обе не повод
        // поднимать туннель «на всякий случай».
        else -> Command.UNKNOWN
    }

    /**
     * @param tunnelActive туннель реально работает (есть интерфейс и читающий цикл)
     */
    fun reaction(command: Command, tunnelActive: Boolean): Reaction = when (command) {
        Command.CONNECT -> if (tunnelActive) Reaction.KEEP_TUNNEL else Reaction.START_TUNNEL
        Command.DISCONNECT -> Reaction.STOP_TUNNEL
        // Живой туннель пустым интентом не убиваем: система может пересоздать сервис раньше,
        // чем мы сами поймём, что он ещё нужен.
        Command.UNKNOWN -> if (tunnelActive) Reaction.KEEP_TUNNEL else Reaction.STOP_SERVICE
    }
}
