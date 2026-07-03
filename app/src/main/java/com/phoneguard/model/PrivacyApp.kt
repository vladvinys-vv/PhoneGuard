package com.phoneguard.model

import android.graphics.drawable.Drawable
import android.Manifest

data class AppPermission(
    val name: String,
    val isDangerous: Boolean,
    val lastUsedTime: Long? = null
)

enum class AppCategory {
    TOOLS, SOCIAL, ENTERTAINMENT, PRODUCTIVITY, UTILITIES, UNKNOWN
}

data class PrivacyApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val permissions: List<AppPermission>,
    val hasExcessivePermissions: Boolean,
    val installTime: Long,
    val category: AppCategory
) {
    val dangerousPermissionCount: Int
        get() = permissions.count { it.isDangerous }
}

object PermissionHeuristics {
    private val categoryExpectedPermissions = mapOf(
        AppCategory.TOOLS to listOf(
            Manifest.permission.CAMERA, Manifest.permission.FLASHLIGHT,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE
        ),
        AppCategory.SOCIAL to listOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION
        ),
        AppCategory.ENTERTAINMENT to listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET
        ),
        AppCategory.PRODUCTIVITY to listOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_FINE_LOCATION
        ),
        AppCategory.UTILITIES to listOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )
    )

    fun hasExcessivePermissions(app: PrivacyApp): Boolean {
        val expected = categoryExpectedPermissions[app.category] ?: emptyList()
        val unexpectedPermissions = app.permissions
            .filter { it.isDangerous }
            .filter { !expected.contains(it.name) }
        return unexpectedPermissions.isNotEmpty()
    }
}
