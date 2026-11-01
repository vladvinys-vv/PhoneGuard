package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Идентификаторы уведомлений не должны пересекаться.
 *
 * История: `1001` был одновременно у VPN и у «звонок заблокирован», а диапазон
 * новых пакетов `2000..6095` накрывал `4101` — подтверждение подмены SIM. Оба раза
 * уведомление просто исчезало, никакой ошибки не возникало.
 */
class NotificationIdsTest {

    private val fixedIds = listOf(
        "VPN_FOREGROUND" to NotificationIds.VPN_FOREGROUND,
        "SHOULDER_SURFER_FOREGROUND" to NotificationIds.SHOULDER_SURFER_FOREGROUND,
        "SHOULDER_SURFER_ALERT" to NotificationIds.SHOULDER_SURFER_ALERT,
        "BLOCKED_CALL" to NotificationIds.BLOCKED_CALL,
        "BLOCKED_SMS" to NotificationIds.BLOCKED_SMS,
        "SIM_SWAP" to NotificationIds.SIM_SWAP,
        "SMS_ALARM" to NotificationIds.SMS_ALARM,
        "STEALTH_CAMERA" to NotificationIds.STEALTH_CAMERA
    )

    @Test
    fun fixedIdsAreUnique() {
        val duplicates = fixedIds.groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue("дубли: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun packageAddedRangeDoesNotOverlapFixedIds() {
        fixedIds.forEach { (name, id) ->
            assertTrue(
                "$name=$id попадает в диапазон уведомлений о пакетах ${NotificationIds.PACKAGE_ADDED_RANGE}",
                id !in NotificationIds.PACKAGE_ADDED_RANGE
            )
        }
    }

    @Test
    fun packageAddedIdsStayInsideRange() {
        listOf(
            "com.example.app",
            "org.vendor.translator",
            "a",
            "com.example.with.a.very.long.package.name.for.testing",
            "падение.кириллицей"
        ).forEach { name ->
            assertTrue(
                "$name → ${NotificationIds.packageAdded(name)}",
                NotificationIds.packageAdded(name) in NotificationIds.PACKAGE_ADDED_RANGE
            )
        }
    }

    @Test
    fun packageAddedIdIsStableForSamePackage() {
        assertEquals(
            NotificationIds.packageAdded("com.example.app"),
            NotificationIds.packageAdded("com.example.app")
        )
    }

    @Test
    fun differentPackagesGetDifferentIds() {
        val names = listOf("com.example.app", "com.example.app2", "org.other.tool", "com.spy.alpha")
        val ids = names.map(NotificationIds::packageAdded)
        assertEquals("id не должны совпадать: $ids", names.size, ids.toSet().size)
    }

    @Test
    fun negativeHashCodesStillLandInsideRange() {
        // hashCode() может быть отрицательным — маска обязана это выдержать.
        val negative = generateSequence(0) { it + 1 }
            .map { "com.example.pkg$it" }
            .first { it.hashCode() < 0 }
        assertTrue(
            "$negative (${negative.hashCode()}) → ${NotificationIds.packageAdded(negative)}",
            NotificationIds.packageAdded(negative) in NotificationIds.PACKAGE_ADDED_RANGE
        )
    }
}
