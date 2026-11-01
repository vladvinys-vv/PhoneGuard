package com.phoneguard.antitheft

import com.phoneguard.antitheft.LocationAccuracyPolicy.Accuracy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Выбор точности геолокации по выданным разрешениям.
 *
 * Регресс: при разрешении «примерно» (только COARSE) запрос
 * `PRIORITY_HIGH_ACCURACY` бросает `SecurityException`, и анти-воровская команда
 * отвечала «координаты недоступны», хотя точка была доступна.
 */
class LocationAccuracyPolicyTest {

    @Test
    fun finePermissionAllowsHighAccuracy() {
        assertEquals(Accuracy.HIGH, LocationAccuracyPolicy.accuracyFor(fineGranted = true, coarseGranted = true))
        assertEquals(
            "FINE всегда выдаётся вместе с COARSE, но проверяем и отдельный случай",
            Accuracy.HIGH,
            LocationAccuracyPolicy.accuracyFor(fineGranted = true, coarseGranted = false)
        )
    }

    @Test
    fun coarseOnlyFallsBackToBalanced() {
        assertEquals(Accuracy.BALANCED, LocationAccuracyPolicy.accuracyFor(fineGranted = false, coarseGranted = true))
    }

    @Test
    fun noPermissionMeansNoRequest() {
        assertEquals(Accuracy.NONE, LocationAccuracyPolicy.accuracyFor(fineGranted = false, coarseGranted = false))
    }
}
