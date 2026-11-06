package com.phoneguard.perm

import com.phoneguard.perm.ProtectionActionPolicy.Action
import com.phoneguard.protect.SensitiveAppPolicy.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Какое действие предложить, когда защита работает не полностью.
 *
 * Главное, что здесь проверяется, — **отсутствие действий-пустышек**: после «больше не
 * спрашивать» Android не покажет второй диалог разрешения, и кнопка «Разрешить» в этом
 * состоянии молча ничего не делает. Такой отказ труднее заметить, чем ошибку: экран выглядит
 * рабочим, а защиты нет.
 */
class ProtectionActionPolicyTest {

    @Test
    fun `granted permission needs no action`() {
        assertNull(ProtectionActionPolicy.permissionAction(granted = true, askedBefore = true, canAskAgain = false))
    }

    @Test
    fun `never asked permission is requested`() {
        assertEquals(
            Action.REQUEST_PERMISSION,
            ProtectionActionPolicy.permissionAction(granted = false, askedBefore = false, canAskAgain = false)
        )
    }

    @Test
    fun `denied once but askable is requested again`() {
        assertEquals(
            Action.REQUEST_PERMISSION,
            ProtectionActionPolicy.permissionAction(granted = false, askedBefore = true, canAskAgain = true)
        )
    }

    @Test
    fun `denied permanently opens app settings`() {
        // Ключевой случай: диалог больше не появится, значит нужен экран приложения.
        assertEquals(
            Action.OPEN_APP_DETAILS,
            ProtectionActionPolicy.permissionAction(granted = false, askedBefore = true, canAskAgain = false)
        )
    }

    @Test
    fun `notifications disabled win over granted permission`() {
        // Разрешение выдано, но показ выключен — системный диалог бессмысленен.
        assertEquals(
            Action.OPEN_NOTIFICATION_SETTINGS,
            ProtectionActionPolicy.notificationAction(
                permissionGranted = true,
                appNotificationsEnabled = false,
                askedBefore = true,
                canAskAgain = false
            )
        )
    }

    @Test
    fun `notifications missing permission is requested`() {
        assertEquals(
            Action.REQUEST_PERMISSION,
            ProtectionActionPolicy.notificationAction(
                permissionGranted = false,
                appNotificationsEnabled = true,
                askedBefore = false,
                canAskAgain = false
            )
        )
    }

    @Test
    fun `notifications denied permanently opens app settings`() {
        assertEquals(
            Action.OPEN_APP_DETAILS,
            ProtectionActionPolicy.notificationAction(
                permissionGranted = false,
                appNotificationsEnabled = true,
                askedBefore = true,
                canAskAgain = false
            )
        )
    }

    @Test
    fun `notifications fully working need no action`() {
        assertNull(
            ProtectionActionPolicy.notificationAction(
                permissionGranted = true,
                appNotificationsEnabled = true,
                askedBefore = true,
                canAskAgain = false
            )
        )
    }

    @Test
    fun `every capability of a dangerous neighbour leads to its own system screen`() {
        assertEquals(Action.OPEN_OVERLAY_SETTINGS, ProtectionActionPolicy.exposureAction(Capability.OVERLAY))
        assertEquals(
            Action.OPEN_ACCESSIBILITY_SETTINGS,
            ProtectionActionPolicy.exposureAction(Capability.ACCESSIBILITY)
        )
        assertEquals(
            Action.OPEN_NOTIFICATION_ACCESS_SETTINGS,
            ProtectionActionPolicy.exposureAction(Capability.NOTIFICATION_ACCESS)
        )
        assertEquals(
            Action.OPEN_DEVICE_ADMIN_SETTINGS,
            ProtectionActionPolicy.exposureAction(Capability.DEVICE_ADMIN)
        )
    }

    @Test
    fun `exposure action uses the most dangerous capability`() {
        // Список уже отсортирован по важности права: сначала то, что и предлагаем отозвать.
        assertEquals(
            Action.OPEN_OVERLAY_SETTINGS,
            ProtectionActionPolicy.exposureAction(
                listOf(Capability.OVERLAY, Capability.NOTIFICATION_ACCESS)
            )
        )
    }

    @Test
    fun `exposure without capabilities proposes nothing`() {
        assertNull(ProtectionActionPolicy.exposureAction(emptyList()))
    }

    @Test
    fun `all exposure actions open system settings`() {
        Capability.entries.forEach { capability ->
            val action = ProtectionActionPolicy.exposureAction(capability)
            assertTrue("$capability → $action", ProtectionActionPolicy.opensSystemSettings(action))
        }
    }

    @Test
    fun `request permission is the only action that does not open settings`() {
        assertFalse(ProtectionActionPolicy.opensSystemSettings(Action.REQUEST_PERMISSION))
        Action.entries
            .filter { it != Action.REQUEST_PERMISSION }
            .forEach { assertTrue("$it должен вести в настройки", ProtectionActionPolicy.opensSystemSettings(it)) }
    }

    @Test
    fun `degraded capabilities from the policy always resolve to some action`() {
        // Связь двух политик: каждая ослабленная возможность обязана иметь действие, иначе
        // карточка на дашборде снова станет текстом без кнопки.
        val degraded = ProtectionCapabilityPolicy.degraded(
            granted = emptySet(),
            sdkInt = ProtectionCapabilityPolicy.NOTIFICATIONS_RUNTIME_FROM_SDK
        )
        assertTrue("список ослабленных пуст — проверять нечего", degraded.isNotEmpty())
        degraded.forEach { capability ->
            assertNotNull(
                "у $capability нет разрешения в таблице политики",
                ProtectionCapabilityPolicy.permissionOf(
                    capability,
                    ProtectionCapabilityPolicy.NOTIFICATIONS_RUNTIME_FROM_SDK
                )
            )
        }
    }
}
