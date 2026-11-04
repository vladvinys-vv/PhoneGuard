package com.phoneguard.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Реакция сервиса VPN на команды и на перезапуск системой.
 *
 * Дыра, которую здесь фиксируем: `START_STICKY` перезапускает сервис **с пустым интентом**, а
 * прежняя ветка `else` не запускала туннель и не завершала сервис — он висел без уведомления,
 * хотя `startForegroundService` требует `startForeground()` в первые пять секунд.
 */
class VpnLifecyclePolicyTest {

    private val connect = "com.phoneguard.CONNECT"
    private val disconnect = "com.phoneguard.DISCONNECT"

    private fun command(action: String?) =
        VpnLifecyclePolicy.commandOf(action, connect, disconnect)

    @Test
    fun connectCommandIsRecognised() {
        assertEquals(VpnLifecyclePolicy.Command.CONNECT, command(connect))
    }

    @Test
    fun disconnectCommandIsRecognised() {
        assertEquals(VpnLifecyclePolicy.Command.DISCONNECT, command(disconnect))
    }

    @Test
    fun emptyAndForeignIntentsAreUnknown() {
        // Пустой интент — перезапуск системы; чужая строка — не наша команда.
        assertEquals(VpnLifecyclePolicy.Command.UNKNOWN, command(null))
        assertEquals(VpnLifecyclePolicy.Command.UNKNOWN, command(""))
        assertEquals(VpnLifecyclePolicy.Command.UNKNOWN, command("com.other.SOMETHING"))
    }

    @Test
    fun connectStartsTunnelWhenIdle() {
        assertEquals(
            VpnLifecyclePolicy.Reaction.START_TUNNEL,
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.CONNECT, tunnelActive = false)
        )
    }

    @Test
    fun connectOnRunningTunnelDoesNotRestartIt() {
        // Повторный CONNECT не должен пересоздавать работающий туннель: пользователь потерял бы сеть.
        assertEquals(
            VpnLifecyclePolicy.Reaction.KEEP_TUNNEL,
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.CONNECT, tunnelActive = true)
        )
    }

    @Test
    fun disconnectAlwaysStopsTunnel() {
        assertEquals(
            VpnLifecyclePolicy.Reaction.STOP_TUNNEL,
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.DISCONNECT, tunnelActive = true)
        )
        assertEquals(
            VpnLifecyclePolicy.Reaction.STOP_TUNNEL,
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.DISCONNECT, tunnelActive = false)
        )
    }

    @Test
    fun stickyRestartWithoutTunnelStopsService() {
        // Главное исправление: сервис не висит без уведомления после перезапуска системой.
        assertEquals(
            VpnLifecyclePolicy.Reaction.STOP_SERVICE,
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.UNKNOWN, tunnelActive = false)
        )
    }

    @Test
    fun unknownIntentDoesNotKillRunningTunnel() {
        // Живой туннель пустым интентом не убиваем: система может пересоздать сервис
        // раньше, чем приложение само поймёт, что туннель ещё нужен.
        assertEquals(
            VpnLifecyclePolicy.Reaction.KEEP_TUNNEL,
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.UNKNOWN, tunnelActive = true)
        )
    }

    @Test
    fun onlyConnectEverStartsTunnel() {
        for (cmd in VpnLifecyclePolicy.Command.values()) {
            for (active in listOf(false, true)) {
                val reaction = VpnLifecyclePolicy.reaction(cmd, active)
                if (reaction == VpnLifecyclePolicy.Reaction.START_TUNNEL) {
                    assertEquals("туннель поднимает только CONNECT", VpnLifecyclePolicy.Command.CONNECT, cmd)
                    assertEquals("и только если его нет", false, active)
                }
            }
        }
    }

    @Test
    fun serviceIsStoppedOnlyWithoutCommandAndWithoutTunnel() {
        for (cmd in VpnLifecyclePolicy.Command.values()) {
            for (active in listOf(false, true)) {
                val reaction = VpnLifecyclePolicy.reaction(cmd, active)
                if (reaction == VpnLifecyclePolicy.Reaction.STOP_SERVICE) {
                    assertEquals(VpnLifecyclePolicy.Command.UNKNOWN, cmd)
                    assertEquals(false, active)
                }
            }
        }
    }

    @Test
    fun tellingTunnelStatesApartMatters() {
        assertNotEquals(
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.CONNECT, tunnelActive = false),
            VpnLifecyclePolicy.reaction(VpnLifecyclePolicy.Command.CONNECT, tunnelActive = true)
        )
    }
}
