package com.phoneguard.fullscan.checks

import com.phoneguard.fullscan.checks.PermissionRiskPolicy.Group
import com.phoneguard.fullscan.checks.PermissionRiskPolicy.Level
import com.phoneguard.fullscan.checks.PermissionRiskPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила «какие наборы разрешений считать шпионским ПО».
 *
 * Раньше они проверялись только Robolectric-тестом, который в офлайн-прогоне не
 * выполняется, и в нём была ошибка: `SMS + PHONE` ждали как `HIGH`, хотя комбинация
 * входит в список критических. Здесь правила проверяются обычным JVM-прогоном.
 */
class PermissionRiskPolicyTest {

    @Test
    fun `камера микрофон и геолокация - критично`() {
        val verdict = PermissionRiskPolicy.assess(setOf(Group.CAMERA, Group.MICROPHONE, Group.LOCATION))

        assertEquals(Level.CRITICAL, verdict?.level)
        assertEquals(Reason.CRITICAL_COMBO, verdict?.reason)
        assertEquals(setOf(Group.CAMERA, Group.MICROPHONE, Group.LOCATION), verdict?.matched)
    }

    @Test
    fun `смс и состояние телефона - критично`() {
        val verdict = PermissionRiskPolicy.assess(setOf(Group.SMS, Group.PHONE))

        assertEquals(Level.CRITICAL, verdict?.level)
        assertEquals(setOf(Group.SMS, Group.PHONE), verdict?.matched)
    }

    @Test
    fun `микрофон и геолокация - критично`() {
        assertEquals(
            Level.CRITICAL,
            PermissionRiskPolicy.assess(setOf(Group.MICROPHONE, Group.LOCATION))?.level
        )
    }

    @Test
    fun `камера и геолокация - высокий риск`() {
        // Раньше такой набор не попадал в отчёт вообще: критической комбинации нет,
        // SMS нет, групп меньше трёх.
        val verdict = PermissionRiskPolicy.assess(setOf(Group.CAMERA, Group.LOCATION))

        assertEquals(Level.HIGH, verdict?.level)
        assertEquals(Reason.SENSITIVE_PAIR, verdict?.reason)
        assertEquals(setOf(Group.CAMERA, Group.LOCATION), verdict?.matched)
    }

    @Test
    fun `контакты и геолокация - высокий риск`() {
        val verdict = PermissionRiskPolicy.assess(setOf(Group.CONTACTS, Group.LOCATION))

        assertEquals(Level.HIGH, verdict?.level)
        assertEquals(Reason.SENSITIVE_PAIR, verdict?.reason)
    }

    @Test
    fun `смс с любым другим разрешением - высокий риск`() {
        val verdict = PermissionRiskPolicy.assess(setOf(Group.SMS, Group.STORAGE))

        assertEquals(Level.HIGH, verdict?.level)
        assertEquals(Reason.SMS_PLUS_OTHER, verdict?.reason)
    }

    @Test
    fun `только геолокация - средний риск`() {
        val verdict = PermissionRiskPolicy.assess(setOf(Group.LOCATION))

        assertEquals(Level.MEDIUM, verdict?.level)
        assertEquals(Reason.BACKGROUND_LOCATION, verdict?.reason)
    }

    @Test
    fun `три нечувствительных разрешения - всё равно высокий риск`() {
        // Хранилище, состояние телефона и камера: «следящей» пары нет, но групп три.
        val verdict = PermissionRiskPolicy.assess(setOf(Group.STORAGE, Group.PHONE, Group.CAMERA))

        assertEquals(Level.HIGH, verdict?.level)
        assertEquals(Reason.MANY_SENSITIVE, verdict?.reason)
    }

    @Test
    fun `одно безобидное разрешение не даёт риска`() {
        assertNull(PermissionRiskPolicy.assess(setOf(Group.CONTACTS)))
        assertNull(PermissionRiskPolicy.assess(setOf(Group.STORAGE)))
        assertNull(PermissionRiskPolicy.assess(setOf(Group.PHONE)))
    }

    @Test
    fun `пустой набор не даёт риска`() {
        assertNull(PermissionRiskPolicy.assess(emptySet()))
    }

    @Test
    fun `хранилище и камера без слежки остаются без риска`() {
        // Снимки в галерее — обычная функция, а не шпионаж: пары с STORAGE не считаем.
        assertNull(PermissionRiskPolicy.assess(setOf(Group.STORAGE, Group.CAMERA)))
    }

    @Test
    fun `уровень всегда соответствует причине`() {
        val cases = listOf(
            setOf(Group.CAMERA, Group.MICROPHONE) to Level.CRITICAL,
            setOf(Group.CAMERA, Group.LOCATION) to Level.HIGH,
            setOf(Group.LOCATION) to Level.MEDIUM
        )
        cases.forEach { (groups, expected) ->
            val verdict = PermissionRiskPolicy.assess(groups)
            assertEquals(expected, verdict?.level)
            assertTrue("совпавшие группы должны быть подмножеством выданных", groups.containsAll(verdict!!.matched))
        }
    }
}
