package com.phoneguard.fullscan.checks

import android.Manifest
import android.content.pm.ApplicationInfo
import com.phoneguard.fullscan.checks.PermissionAbuseCheck.PermissionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила отбора приложений для проверки опасных разрешений.
 *
 * Тест выполняется на обычной JVM (без Robolectric и MockK): правила специально вынесены в
 * [PermissionAbuseSweep], чтобы их можно было прогнать там, где нет Android-рантайма.
 *
 * Что здесь важно:
 *  - системное приложение не должно попадать в отчёт (иначе «критических» находок десяток
 *    на любом телефоне);
 *  - считаются только **выданные** разрешения: заявленное в манифесте, но не выданное
 *    разрешение раньше делало обычное приложение «шпионским»;
 *  - группа срабатывает по любому своему разрешению, а не по всему списку.
 */
class PermissionAbuseSweepTest {

    private val granted: MutableSet<String> = mutableSetOf()

    private fun groupsOf(vararg requested: String): Set<PermissionGroup> =
        PermissionAbuseSweep.grantedGroups(requested.toList()) { it in granted }

    @Test
    fun `system app flags are recognised`() {
        assertTrue(PermissionAbuseSweep.isSystemApp(ApplicationInfo.FLAG_SYSTEM))
        assertTrue(PermissionAbuseSweep.isSystemApp(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        // Оба флага одновременно — тоже системное приложение.
        assertTrue(
            PermissionAbuseSweep.isSystemApp(
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            )
        )
    }

    @Test
    fun `user app flags are not mistaken for system`() {
        assertFalse(PermissionAbuseSweep.isSystemApp(0))
        // Флаг отлаживаемости и внешнего хранилища не делает приложение системным:
        // раньше проверка была «flags and FLAG_SYSTEM != 0», но с любым другим флагом
        // её легко было сломать при рефакторинге.
        assertFalse(
            PermissionAbuseSweep.isSystemApp(
                ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_EXTERNAL_STORAGE
            )
        )
    }

    @Test
    fun `requested but not granted permissions are ignored`() {
        // Приложение заявило камеру и микрофон, но пользователь ничего не выдал.
        val groups = groupsOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        assertTrue("ни одного выданного разрешения — ни одной группы: $groups", groups.isEmpty())
    }

    @Test
    fun `granted camera and microphone form two groups`() {
        granted += Manifest.permission.CAMERA
        granted += Manifest.permission.RECORD_AUDIO

        assertEquals(
            setOf(PermissionGroup.CAMERA, PermissionGroup.MICROPHONE),
            groupsOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    @Test
    fun `partially granted group is still reported`() {
        // Группа «локация» содержит три разрешения; достаточно одного выданного.
        granted += Manifest.permission.ACCESS_COARSE_LOCATION

        assertEquals(
            setOf(PermissionGroup.LOCATION),
            groupsOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        )
    }

    @Test
    fun `phone group triggers on a single member`() {
        granted += Manifest.permission.READ_PHONE_NUMBERS

        assertEquals(
            setOf(PermissionGroup.PHONE),
            groupsOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
        )
    }

    @Test
    fun `notifications are not a dangerous group`() {
        // POST_NOTIFICATIONS нет в каталоге намеренно: раньше оно давало ложные срабатывания.
        granted += Manifest.permission.POST_NOTIFICATIONS

        assertTrue(groupsOf(Manifest.permission.POST_NOTIFICATIONS).isEmpty())
    }

    @Test
    fun `group order follows the catalogue`() {
        granted += Manifest.permission.READ_SMS
        granted += Manifest.permission.CAMERA

        // Порядок не зависит от порядка запрошенных разрешений: объяснение в отчёте
        // должно читаться одинаково от запуска к запуску.
        val first = groupsOf(Manifest.permission.READ_SMS, Manifest.permission.CAMERA).toList()
        val second = groupsOf(Manifest.permission.CAMERA, Manifest.permission.READ_SMS).toList()

        assertEquals(first, second)
        assertEquals(listOf(PermissionGroup.CAMERA, PermissionGroup.SMS), first)
    }

    @Test
    fun `no requested permissions means no permission checks at all`() {
        var checks = 0

        val groups = PermissionAbuseSweep.grantedGroups(emptyList()) {
            checks++
            true
        }

        assertTrue(groups.isEmpty())
        // `checkPermission` — это IPC в system_server; на пустом списке его не должно быть.
        assertEquals(0, checks)
    }

    @Test
    fun `granted permission outside the catalogue is ignored`() {
        granted += "android.permission.FLASHLIGHT"

        assertTrue(groupsOf("android.permission.FLASHLIGHT").isEmpty())
    }

    @Test
    fun `unknown permission mixed with catalog permissions keeps only known groups`() {
        granted += Manifest.permission.READ_CONTACTS
        granted += "com.example.CUSTOM_PERMISSION"

        assertEquals(
            setOf(PermissionGroup.CONTACTS),
            groupsOf(Manifest.permission.READ_CONTACTS, "com.example.CUSTOM_PERMISSION")
        )
    }
}
