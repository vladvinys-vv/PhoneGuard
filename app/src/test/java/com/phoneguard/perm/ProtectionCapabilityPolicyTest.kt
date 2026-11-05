package com.phoneguard.perm

import com.phoneguard.perm.ProtectionCapabilityPolicy.Capability
import com.phoneguard.perm.ProtectionCapabilityPolicy.CapabilityPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Какие возможности защиты ломаются без разрешений.
 *
 * Разрешения запрашивались один раз на онбординге, и отзыв `CAMERA`/`SEND_SMS`/`READ_PHONE_STATE`/
 * `POST_NOTIFICATIONS` раньше ничем не проявлялся: функция просто переставала работать.
 */
class ProtectionCapabilityPolicyTest {

    private val all = CapabilityPermission.values().toSet()

    @Test
    fun everythingGrantedMeansNothingDegraded() {
        assertEquals(emptyList<Capability>(), ProtectionCapabilityPolicy.degraded(all, sdkInt = 34))
        assertTrue(ProtectionCapabilityPolicy.isFullyProtected(all, sdkInt = 34))
    }

    @Test
    fun missingPhoneStateDisablesSimSwapDetection() {
        assertEquals(
            listOf(Capability.SIM_SWAP_DETECTION),
            ProtectionCapabilityPolicy.degraded(all - CapabilityPermission.PHONE_STATE, sdkInt = 34)
        )
    }

    @Test
    fun missingCameraDisablesIntrusionPhoto() {
        assertEquals(
            listOf(Capability.INTRUSION_PHOTO),
            ProtectionCapabilityPolicy.degraded(all - CapabilityPermission.CAMERA, sdkInt = 34)
        )
    }

    @Test
    fun missingSmsDisablesSmsToContacts() {
        assertEquals(
            listOf(Capability.INTRUSION_SMS),
            ProtectionCapabilityPolicy.degraded(all - CapabilityPermission.SEND_SMS, sdkInt = 34)
        )
    }

    @Test
    fun missingLocationDisablesTrustedContactLocation() {
        assertEquals(
            listOf(Capability.TRUSTED_CONTACT_LOCATION),
            ProtectionCapabilityPolicy.degraded(
                all - CapabilityPermission.FINE_LOCATION,
                sdkInt = 34
            )
        )
    }

    @Test
    fun notificationsMatterOnlyFromAndroid13() {
        // До Android 13 такого разрешения нет: предупреждать о нём было бы ложной тревогой.
        assertEquals(
            emptyList<Capability>(),
            ProtectionCapabilityPolicy.degraded(all - CapabilityPermission.POST_NOTIFICATIONS, sdkInt = 32)
        )
        assertEquals(
            listOf(Capability.INTRUSION_WARNING),
            ProtectionCapabilityPolicy.degraded(all - CapabilityPermission.POST_NOTIFICATIONS, sdkInt = 33)
        )
        // Ровно с 33 и далее — включая будущие версии.
        assertEquals(
            listOf(Capability.INTRUSION_WARNING),
            ProtectionCapabilityPolicy.degraded(all - CapabilityPermission.POST_NOTIFICATIONS, sdkInt = 35)
        )
    }

    @Test
    fun severalMissingPermissionsAreReportedInDeclarationOrder() {
        val granted = setOf(
            CapabilityPermission.POST_NOTIFICATIONS,
            CapabilityPermission.FINE_LOCATION
        )
        // Порядок — как в [Capability], а не как повезёт множеству.
        assertEquals(
            listOf(
                Capability.SIM_SWAP_DETECTION,
                Capability.INTRUSION_PHOTO,
                Capability.INTRUSION_SMS
            ),
            ProtectionCapabilityPolicy.degraded(granted, sdkInt = 34)
        )
    }

    @Test
    fun nothingGrantedReportsEveryCapability() {
        val degraded = ProtectionCapabilityPolicy.degraded(emptySet(), sdkInt = 34)
        assertEquals(Capability.values().toList(), degraded)
        assertFalse(ProtectionCapabilityPolicy.isFullyProtected(emptySet(), sdkInt = 34))
    }

    @Test
    fun duplicatesNeverAppear() {
        // Одно разрешение может влиять на несколько функций — каждая строка должна быть однажды.
        val degraded = ProtectionCapabilityPolicy.degraded(emptySet(), sdkInt = 34)
        assertEquals(degraded.size, degraded.toSet().size)
    }

    @Test
    fun requirementsHaveNoUnknownPermissions() {
        val known = CapabilityPermission.values().toSet()
        for (requirement in ProtectionCapabilityPolicy.requirements(sdkInt = 34)) {
            assertTrue(requirement.permission in known)
        }
    }

    @Test
    fun oldAndroidHasNoNotificationRequirement() {
        val capabilities = ProtectionCapabilityPolicy.requirements(sdkInt = 30).map { it.capability }
        assertFalse(capabilities.contains(Capability.INTRUSION_WARNING))
        assertEquals(4, capabilities.size)
    }
}
