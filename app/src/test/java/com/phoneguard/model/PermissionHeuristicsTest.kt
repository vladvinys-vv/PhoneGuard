package com.phoneguard.model

import android.Manifest
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оценка «избыточных разрешений»: опасное право, не ожидаемое для категории приложения.
 *
 * Раньше файл не исполнялся: иконка в модели — `Drawable`, и тест подставлял MockK.
 * Теперь в JVM-прогоне есть заглушка `Drawable`, и проверки идут на настоящей логике.
 */
class PermissionHeuristicsTest {

    /** Полная реализация обязательных методов: иначе файл не собирается с настоящим `android.jar`. */
    private val icon: Drawable = object : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private fun app(
        category: AppCategory,
        permissions: List<AppPermission>,
        packageName: String = "com.example.app"
    ) = PrivacyApp(
        packageName = packageName,
        appName = "App",
        icon = icon,
        permissions = permissions,
        hasExcessivePermissions = false,
        installTime = 0,
        category = category
    )

    private fun dangerous(vararg names: String) = names.map { AppPermission(it, isDangerous = true) }

    @Test
    fun toolsAppWithOnlyExpectedPermissionsIsNotExcessive() {
        val tool = app(
            AppCategory.TOOLS,
            dangerous(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(tool))
    }

    @Test
    fun socialAppWithUnexpectedDangerousPermissionIsExcessive() {
        val social = app(
            AppCategory.SOCIAL,
            dangerous(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.SEND_SMS // для соцсети не ожидается
            )
        )
        assertTrue(PermissionHeuristics.hasExcessivePermissions(social))
    }

    @Test
    fun unknownCategoryWithNoDangerousPermissionsIsNotExcessive() {
        val unknown = app(AppCategory.UNKNOWN, emptyList())
        assertFalse(PermissionHeuristics.hasExcessivePermissions(unknown))
    }

    @Test
    fun unknownCategoryWithDangerousPermissionIsExcessive() {
        // Для неизвестной категории «ожидаемых» прав нет: любое опасное — избыточное.
        val unknown = app(AppCategory.UNKNOWN, dangerous(Manifest.permission.CAMERA))
        assertTrue(PermissionHeuristics.hasExcessivePermissions(unknown))
    }

    @Test
    fun socialAppWithOnlySafePermissionsIsNotExcessive() {
        val social = app(
            AppCategory.SOCIAL,
            listOf(
                AppPermission("android.permission.INTERNET", isDangerous = false),
                AppPermission("android.permission.ACCESS_NETWORK_STATE", isDangerous = false)
            )
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(social))
    }

    @Test
    fun nonDangerousPermissionIsNeverCounted() {
        // Флаг `isDangerous` решает всё: имя права само по себе ничего не значит.
        val social = app(
            AppCategory.SOCIAL,
            listOf(AppPermission(Manifest.permission.SEND_SMS, isDangerous = false))
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(social))
    }

    @Test
    fun entertainmentAppWithLocationIsExcessive() {
        val media = app(
            AppCategory.ENTERTAINMENT,
            dangerous(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
        assertTrue(PermissionHeuristics.hasExcessivePermissions(media))
    }

    @Test
    fun utilitiesAppExpectsStorageAndCamera() {
        val utilities = app(
            AppCategory.UTILITIES,
            dangerous(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA
            )
        )
        assertFalse(PermissionHeuristics.hasExcessivePermissions(utilities))
    }

    @Test
    fun dangerousPermissionCountIgnoresSafeOnes() {
        val mixed = app(
            AppCategory.TOOLS,
            listOf(
                AppPermission(Manifest.permission.CAMERA, isDangerous = true),
                AppPermission(Manifest.permission.INTERNET, isDangerous = false),
                AppPermission(Manifest.permission.READ_SMS, isDangerous = true)
            )
        )
        assertEquals(2, mixed.dangerousPermissionCount)
    }
}
