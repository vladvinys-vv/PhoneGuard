package com.phoneguard.util

import com.phoneguard.util.BackgroundStartPolicy.Fallback
import com.phoneguard.util.BackgroundStartPolicy.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Можно ли поднять foreground-сервис из данного места.
 *
 * Повод: на Android 12+ запуск сервиса из фона бросает `ForegroundServiceStartNotAllowedException`,
 * и это уже трижды обрабатывалось по-своему. Хуже — `stop()` у детектора подглядывания и
 * `stopVpn()` вызывали `startService()`, который из фона бросает `IllegalStateException`:
 * интерфейс показывал «выключено», а сервис продолжал работать.
 */
class BackgroundStartPolicyTest {

    @Test
    fun `before android 12 background starts are unrestricted`() {
        Trigger.values().forEach { trigger ->
            assertTrue(
                "$trigger на Android 11 должен запускаться",
                BackgroundStartPolicy.mayStartForeground(trigger, sdkInt = 30, isDeviceAdmin = false)
            )
        }
    }

    @Test
    fun `user visible action is always allowed`() {
        assertTrue(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.USER_VISIBLE, sdkInt = 34, isDeviceAdmin = false
            )
        )
    }

    @Test
    fun `boot broadcast is allowed`() {
        assertTrue(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.BOOT_COMPLETED, sdkInt = 34, isDeviceAdmin = false
            )
        )
    }

    @Test
    fun `sms command needs device admin`() {
        assertFalse(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.SMS_COMMAND, sdkInt = 34, isDeviceAdmin = false
            )
        )
        // Device admin — исключение из ограничений: анти-кража обязана звучать по SMS.
        assertTrue(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.SMS_COMMAND, sdkInt = 34, isDeviceAdmin = true
            )
        )
    }

    @Test
    fun `device admin trigger depends on admin being active`() {
        assertTrue(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.DEVICE_ADMIN, sdkInt = 31, isDeviceAdmin = true
            )
        )
        assertFalse(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.DEVICE_ADMIN, sdkInt = 31, isDeviceAdmin = false
            )
        )
    }

    @Test
    fun `worker cannot start foreground service directly`() {
        // WorkManager идёт по expedited-пути; прямой foreground-старт из воркера запрещён.
        assertFalse(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.WORKER, sdkInt = 34, isDeviceAdmin = true
            )
        )
    }

    @Test
    fun `unknown origin is treated as background`() {
        assertFalse(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.UNKNOWN, sdkInt = 31, isDeviceAdmin = true
            )
        )
    }

    @Test
    fun `fallback for user visible reports the error`() {
        assertEquals(Fallback.REPORT_TO_USER, BackgroundStartPolicy.fallback(Trigger.USER_VISIBLE))
    }

    @Test
    fun `fallback for sms command shows a notification`() {
        // Ответить на SMS уже нельзя (оно отправлено) — остаётся локальное уведомление.
        assertEquals(Fallback.NOTIFY_INSTEAD, BackgroundStartPolicy.fallback(Trigger.SMS_COMMAND))
        assertEquals(Fallback.NOTIFY_INSTEAD, BackgroundStartPolicy.fallback(Trigger.UNKNOWN))
    }

    @Test
    fun `fallback for boot and worker defers`() {
        assertEquals(Fallback.DEFER, BackgroundStartPolicy.fallback(Trigger.BOOT_COMPLETED))
        assertEquals(Fallback.DEFER, BackgroundStartPolicy.fallback(Trigger.WORKER))
    }

    @Test
    fun `every trigger has a fallback`() {
        Trigger.values().forEach { trigger ->
            val fallback = BackgroundStartPolicy.fallback(trigger)
            assertTrue("$trigger без запасного варианта", fallback in Fallback.values())
        }
    }

    @Test
    fun `should attempt mirrors may start`() {
        Trigger.values().forEach { trigger ->
            listOf(30, 31, 34).forEach { sdk ->
                listOf(true, false).forEach { admin ->
                    assertEquals(
                        BackgroundStartPolicy.mayStartForeground(trigger, sdk, admin),
                        BackgroundStartPolicy.shouldAttempt(trigger, sdk, admin)
                    )
                }
            }
        }
    }

    @Test
    fun `restrictions start exactly at android 12`() {
        assertEquals(31, BackgroundStartPolicy.RESTRICTIONS_FROM_SDK)
        assertTrue(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.SMS_COMMAND, BackgroundStartPolicy.RESTRICTIONS_FROM_SDK - 1, false
            )
        )
        assertFalse(
            BackgroundStartPolicy.mayStartForeground(
                Trigger.SMS_COMMAND, BackgroundStartPolicy.RESTRICTIONS_FROM_SDK, false
            )
        )
    }
}
