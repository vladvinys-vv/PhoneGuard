package com.phoneguard.intrusion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Канал доставки предупреждения о вторжении.
 *
 * Ошибка здесь невидима в логах: `startActivity()` из фона на Android 10+ не бросает
 * исключение, просто ничего не происходит, и предупреждение о вторжении не видит никто.
 * Поэтому правило выбора вынесено в чистую функцию и закреплено тестами по версиям API.
 */
class IntrusionWarningDeliveryPolicyTest {

    private val lock = IntrusionWarningDeliveryPolicy.BACKGROUND_ACTIVITY_LOCK_SDK

    @Test
    fun foregroundAlwaysShowsActivity() {
        listOf(26, 28, 29, 30, 33, 35, 36).forEach { sdk ->
            assertEquals(
                "API $sdk на переднем плане должен открывать экран",
                WarningDelivery.ACTIVITY,
                IntrusionWarningDeliveryPolicy.choose(sdkInt = sdk, appInForeground = true)
            )
        }
    }

    @Test
    fun backgroundOnModernAndroidUsesNotification() {
        assertEquals(
            WarningDelivery.NOTIFICATION,
            IntrusionWarningDeliveryPolicy.choose(sdkInt = lock, appInForeground = false)
        )
        assertEquals(
            WarningDelivery.NOTIFICATION,
            IntrusionWarningDeliveryPolicy.choose(sdkInt = 35, appInForeground = false)
        )
    }

    @Test
    fun backgroundBeforeApi29StillShowsActivity() {
        // До Android 10 фоновый запуск активити разрешён — экран заметнее уведомления.
        assertEquals(
            WarningDelivery.ACTIVITY,
            IntrusionWarningDeliveryPolicy.choose(sdkInt = lock - 1, appInForeground = false)
        )
        assertEquals(
            WarningDelivery.ACTIVITY,
            IntrusionWarningDeliveryPolicy.choose(sdkInt = 26, appInForeground = false)
        )
    }

    @Test
    fun boundaryIsExactlyApi29() {
        assertEquals(29, lock)
        assertEquals(
            WarningDelivery.ACTIVITY,
            IntrusionWarningDeliveryPolicy.choose(sdkInt = 28, appInForeground = false)
        )
        assertEquals(
            WarningDelivery.NOTIFICATION,
            IntrusionWarningDeliveryPolicy.choose(sdkInt = 29, appInForeground = false)
        )
    }

    @Test
    fun deliveryIsNeverSilent() {
        // Инвариант: любой набор входов даёт ровно один канал — предупреждение не теряется.
        for (sdk in 26..36) {
            for (foreground in listOf(true, false)) {
                val delivery = IntrusionWarningDeliveryPolicy.choose(sdk, foreground)
                assertEquals(
                    "API $sdk, foreground=$foreground",
                    1,
                    WarningDelivery.entries.count { it == delivery }
                )
            }
        }
    }
}
