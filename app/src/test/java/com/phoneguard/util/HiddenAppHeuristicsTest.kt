package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Эвристика «спрятанных» приложений.
 *
 * Раньше любое приложение без launcher-иконки считалось находкой, и в отчёт попадали наборы
 * иконок, виджеты, плагины камеры и Pro-разблокировщики — то есть ложные срабатывания в
 * отчёте для пользователя. Теперь находка требует activity **и** (опасное разрешение
 * **или** имя пакета под системную службу).
 */
class HiddenAppHeuristicsTest {

    @Test
    fun `detects dangerous permissions only among requested ones`() {
        val requested = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET",
            "android.permission.READ_SMS",
        )
        assertEquals(
            setOf("android.permission.RECORD_AUDIO", "android.permission.READ_SMS"),
            HiddenAppHeuristics.dangerousPermissions(requested),
        )
    }

    @Test
    fun `harmless permissions produce no dangerous ones`() {
        val requested = setOf("android.permission.INTERNET", "android.permission.VIBRATE")
        assertTrue(HiddenAppHeuristics.dangerousPermissions(requested).isEmpty())
    }

    @Test
    fun `no permissions produces an empty set`() {
        assertTrue(HiddenAppHeuristics.dangerousPermissions(emptySet()).isEmpty())
    }

    @Test
    fun `every risky permission is treated as dangerous`() {
        HiddenAppHeuristics.RISKY_PERMISSIONS.forEach { permission ->
            assertEquals(setOf(permission), HiddenAppHeuristics.dangerousPermissions(setOf(permission)))
        }
    }

    @Test
    fun `detects package names disguised as system services`() {
        listOf(
            "com.android.system.updater",
            "com.android.service.worker",
            "com.google.service.sync",
            "com.system.update",
            "update.service.client",
            "android.system.helper",
        ).forEach { name ->
            assertTrue("должно быть подозрительным: $name", HiddenAppHeuristics.hasSuspiciousName(name))
        }
    }

    @Test
    fun `package name matching ignores case`() {
        assertTrue(HiddenAppHeuristics.hasSuspiciousName("COM.ANDROID.SYSTEM.Core"))
    }

    @Test
    fun `normal package names are not suspicious`() {
        listOf("com.phoneguard", "org.example.gallery", "com.android.chrome", "net.messenger").forEach { name ->
            assertFalse("не должно быть подозрительным: $name", HiddenAppHeuristics.hasSuspiciousName(name))
        }
    }

    @Test
    fun `library without activities is never a hidden app`() {
        assertFalse(
            HiddenAppHeuristics.isHiddenAppCandidate(
                activitiesCount = 0,
                requestedPermissions = setOf("android.permission.READ_SMS"),
                packageName = "com.android.system.library",
            )
        )
    }

    @Test
    fun `activity plus dangerous permission is a hidden app`() {
        assertTrue(
            HiddenAppHeuristics.isHiddenAppCandidate(
                activitiesCount = 1,
                requestedPermissions = setOf("android.permission.RECORD_AUDIO"),
                packageName = "com.example.camera.plugin",
            )
        )
    }

    @Test
    fun `activity plus suspicious name is a hidden app even without permissions`() {
        assertTrue(
            HiddenAppHeuristics.isHiddenAppCandidate(
                activitiesCount = 2,
                requestedPermissions = emptySet(),
                packageName = "com.google.service.backup",
            )
        )
    }

    @Test
    fun `activity without permissions and innocent name is not a hidden app`() {
        // Набор иконок: activity есть, разрешений опасных нет, имя обычное.
        assertFalse(
            HiddenAppHeuristics.isHiddenAppCandidate(
                activitiesCount = 1,
                requestedPermissions = setOf("android.permission.INTERNET"),
                packageName = "com.example.iconpack.neon",
            )
        )
    }

    @Test
    fun `negative activity count is treated as a library`() {
        assertFalse(
            HiddenAppHeuristics.isHiddenAppCandidate(
                activitiesCount = -1,
                requestedPermissions = setOf("android.permission.SEND_SMS"),
                packageName = "com.android.system.fake",
            )
        )
    }
}
