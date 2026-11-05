package com.phoneguard.util

import com.phoneguard.util.NotificationChannels.ExistingChannel
import com.phoneguard.util.NotificationChannels.Importance
import com.phoneguard.util.NotificationChannels.Spec
import com.phoneguard.util.NotificationChannels.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каналы уведомлений и их «залипание».
 *
 * Раньше id и важность канала были `private const` в девяти местах, а три `ensureChannel()`
 * начинались с `if (getNotificationChannel(id) != null) return` — правки имени и описания не
 * доезжали до пользователя. Кроме того, уведомление о вторжении и предупреждение о подглядывании
 * никто не отменял: событие заканчивалось, а уведомление оставалось.
 */
class NotificationChannelsTest {

    @Test
    fun `channel ids are unique`() {
        val ids = Spec.values().map { it.channelId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `channel ids keep their historical values`() {
        // Менять id нельзя: вместе с ним теряются пользовательские настройки канала.
        assertEquals("phoneguard_vpn_channel", Spec.FIREWALL.channelId)
        assertEquals("stealth_camera_channel", Spec.STEALTH_CAMERA.channelId)
        assertEquals("shoulder_surfer_channel", Spec.SHOULDER_SURFER.channelId)
        assertEquals("shoulder_surfer_alert_channel", Spec.SHOULDER_SURFER_ALERT.channelId)
        assertEquals("call_blocker_channel", Spec.BLOCKED.channelId)
        assertEquals("intrusion_warning_channel", Spec.INTRUSION_WARNING.channelId)
        assertEquals("sms_command_channel", Spec.SMS_COMMANDS.channelId)
        assertEquals("sim_swap_channel", Spec.SIM_SWAP.channelId)
        assertEquals("package_added_channel", Spec.NEW_APP.channelId)
    }

    @Test
    fun `foreground service channels are quiet`() {
        // Постоянные уведомления сервисов не должны звенеть: они висят сутками.
        assertEquals(Importance.LOW, Spec.FIREWALL.importance)
        assertEquals(Importance.LOW, Spec.SHOULDER_SURFER.importance)
        assertEquals(Importance.MIN, Spec.STEALTH_CAMERA.importance)
    }

    @Test
    fun `warning channels are loud`() {
        listOf(Spec.INTRUSION_WARNING, Spec.SHOULDER_SURFER_ALERT, Spec.SIM_SWAP, Spec.SMS_COMMANDS)
            .forEach { assertEquals("${it.channelId} должен быть громким", Importance.HIGH, it.importance) }
    }

    @Test
    fun `lookup by channel id`() {
        assertEquals(Spec.SIM_SWAP, Spec.byChannelId("sim_swap_channel"))
        assertNull(Spec.byChannelId("no_such_channel"))
    }

    @Test
    fun `channels on the same level as required are not reported`() {
        val existing = Spec.values().map { ExistingChannel(it.channelId, it.importance.rank) }
        assertEquals(emptyList<Spec>(), NotificationChannels.silenced(existing))
    }

    @Test
    fun `channel lowered by user is reported`() {
        val existing = listOf(ExistingChannel(Spec.INTRUSION_WARNING.channelId, Importance.LOW.rank))
        assertEquals(listOf(Spec.INTRUSION_WARNING), NotificationChannels.silenced(existing))
    }

    @Test
    fun `disabled channel is reported`() {
        // IMPORTANCE_NONE приходит как -1: канал выключен пользователем целиком.
        val existing = listOf(ExistingChannel(Spec.SIM_SWAP.channelId, -1))
        assertEquals(listOf(Spec.SIM_SWAP), NotificationChannels.silenced(existing))
    }

    @Test
    fun `channel raised above requirement is not reported`() {
        val existing = listOf(ExistingChannel(Spec.FIREWALL.channelId, Importance.HIGH.rank))
        assertEquals(emptyList<Spec>(), NotificationChannels.silenced(existing))
    }

    @Test
    fun `unknown channels are ignored`() {
        val existing = listOf(ExistingChannel("someone_elses_channel", 0))
        assertEquals(emptyList<Spec>(), NotificationChannels.silenced(existing))
        assertEquals(emptyList<Spec>(), NotificationChannels.known(existing))
    }

    @Test
    fun `silenced channels follow declaration order`() {
        val existing = listOf(
            ExistingChannel(Spec.SIM_SWAP.channelId, 0),
            ExistingChannel(Spec.INTRUSION_WARNING.channelId, 1)
        )
        assertEquals(
            listOf(Spec.INTRUSION_WARNING, Spec.SIM_SWAP),
            NotificationChannels.silenced(existing)
        )
    }

    @Test
    fun `stopping the alarm cancels the intrusion warning`() {
        assertEquals(
            listOf(NotificationIds.INTRUSION_WARNING),
            NotificationChannels.cancelledIds(StopReason.ALARM_STOPPED)
        )
        // Сирена, отзвучавшая сама, — то же событие для пользователя.
        assertEquals(
            NotificationChannels.cancelledIds(StopReason.ALARM_STOPPED),
            NotificationChannels.cancelledIds(StopReason.SIREN_EXPIRED)
        )
    }

    @Test
    fun `stopping detection cancels only the shoulder surfer alert`() {
        val ids = NotificationChannels.cancelledIds(StopReason.DETECTION_STOPPED)
        assertEquals(listOf(NotificationIds.SHOULDER_SURFER_ALERT), ids)
        // Постоянное уведомление сервиса гасит сама система вместе с сервисом.
        assertFalse(ids.contains(NotificationIds.SHOULDER_SURFER_FOREGROUND))
    }

    @Test
    fun `cancelled ids never contain duplicates or the wrong events`() {
        StopReason.values().forEach { reason ->
            val ids = NotificationChannels.cancelledIds(reason)
            assertEquals("$reason не должен повторять id", ids.size, ids.toSet().size)
            assertFalse("$reason не должен гасить VPN", ids.contains(NotificationIds.VPN_FOREGROUND))
            assertFalse("$reason не должен гасить SIM", ids.contains(NotificationIds.SIM_SWAP))
            assertTrue("$reason должен что-то гасить", ids.isNotEmpty())
            ids.forEach { assertNotNull(it) }
        }
    }
}
