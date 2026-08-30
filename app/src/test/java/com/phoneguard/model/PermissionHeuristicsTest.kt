package com.phoneguard.model

import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class PermissionHeuristicsTest {

    @Test
    fun `tools app with only expected permissions is not excessive`() {
        val app = PrivacyApp(
            packageName = "com.example.tool",
            appName = "Tool",
            icon = mockk(relaxed = true),
            permissions = listOf(
                AppPermission(android.Manifest.permission.CAMERA, true),
                AppPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE, true)
            ),
            hasExcessivePermissions = false,
            installTime = 0,
            category = AppCategory.TOOLS
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(app))
    }

    @Test
    fun `social app with unexpected dangerous permission is excessive`() {
        val app = PrivacyApp(
            packageName = "com.example.social",
            appName = "Social",
            icon = mockk(relaxed = true),
            permissions = listOf(
                AppPermission(android.Manifest.permission.CAMERA, true),
                AppPermission(android.Manifest.permission.READ_CONTACTS, true),
                AppPermission(android.Manifest.permission.SEND_SMS, true) // unexpected for social
            ),
            hasExcessivePermissions = false,
            installTime = 0,
            category = AppCategory.SOCIAL
        )
        assertTrue(PermissionHeuristics.hasExcessivePermissions(app))
    }

    @Test
    fun `unknown category with no dangerous permissions is not excessive`() {
        val app = PrivacyApp(
            packageName = "com.example.unknown",
            appName = "Unknown",
            icon = mockk(relaxed = true),
            permissions = emptyList(),
            hasExcessivePermissions = false,
            installTime = 0,
            category = AppCategory.UNKNOWN
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(app))
    }

    @Test
    fun `social app with only safe permissions is not excessive`() {
        val app = PrivacyApp(
            packageName = "com.example.social",
            appName = "Social",
            icon = mockk(relaxed = true),
            permissions = listOf(
                AppPermission("android.permission.INTERNET", false),
                AppPermission("android.permission.ACCESS_NETWORK_STATE", false)
            ),
            hasExcessivePermissions = false,
            installTime = 0,
            category = AppCategory.SOCIAL
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(app))
    }

    @Test
    fun `app with non-dangerous permission is not excessive`() {
        val app = PrivacyApp(
            packageName = "com.example.app",
            appName = "App",
            icon = mockk(relaxed = true),
            permissions = listOf(
                AppPermission("android.permission.INTERNET", false)
            ),
            hasExcessivePermissions = false,
            installTime = 0,
            category = AppCategory.UNKNOWN
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(app))
    }
}
