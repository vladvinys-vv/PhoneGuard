package com.phoneguard.protect

import com.phoneguard.protect.SensitiveAppPolicy.Capability
import com.phoneguard.protect.SensitiveAppPolicy.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила уведомления о новом «опасном соседе».
 *
 * Проверяется не «уведомление показывается», а «когда оно **не** показывается»: именно от
 * этого зависит, будут ли эти предупреждения читать. У `PackageAddedReceiver` уже был
 * безусловный вариант, после которого уведомления перестали замечать.
 */
class NeighbourAlertPolicyTest {

    private val now = 1_700_000_000_000L
    private val ownPackage = "com.phoneguard"

    private fun facts(
        packageName: String,
        vararg capabilities: Capability,
        label: String = "",
        isSystem: Boolean = false
    ) = SensitiveAppPolicy.AppFacts(
        packageName = packageName,
        label = label,
        isSystem = isSystem,
        capabilities = capabilities.toSet()
    )

    private fun decide(
        facts: SensitiveAppPolicy.AppFacts?,
        protected: Set<String> = setOf("com.bank.app"),
        lastAlertAt: Long? = null
    ) = NeighbourAlertPolicy.decide(
        facts = facts,
        protected = protected,
        ownPackageName = ownPackage,
        lastAlertAt = lastAlertAt,
        nowMillis = now
    )

    @Test
    fun `unknown app is not reported`() {
        assertNull(decide(null))
    }

    @Test
    fun `own app is not reported`() {
        assertNull(decide(facts(ownPackage, Capability.OVERLAY)))
    }

    @Test
    fun `system app is not reported`() {
        assertNull(decide(facts("com.android.systemui", Capability.OVERLAY, isSystem = true)))
    }

    @Test
    fun `protected app is not a threat to itself`() {
        val protected = setOf("com.bank.app")
        assertNull(decide(facts("com.bank.app", Capability.OVERLAY), protected))
    }

    @Test
    fun `nothing to protect means no warning`() {
        // Без защищённых приложений «опасный сосед» — просто список приложений с правами:
        // такой уведомление превращает защиту в шум.
        assertNull(decide(facts("com.example.app", Capability.OVERLAY), protected = emptySet()))
    }

    @Test
    fun `app without capabilities is not reported`() {
        assertNull(decide(facts("com.example.app")))
    }

    @Test
    fun `device admin alone is too weak for a notification`() {
        assertNull(decide(facts("com.example.app", Capability.DEVICE_ADMIN)))
    }

    @Test
    fun `overlay alone is reported as medium`() {
        val alert = decide(facts("com.example.app", Capability.OVERLAY))
        assertNotNull(alert)
        assertEquals(Severity.MEDIUM, alert!!.severity)
        assertEquals(listOf(Capability.OVERLAY), alert.capabilities)
    }

    @Test
    fun `notification access alone is reported as medium`() {
        val alert = decide(facts("com.example.app", Capability.NOTIFICATION_ACCESS))
        assertEquals(Severity.MEDIUM, alert!!.severity)
    }

    @Test
    fun `accessibility alone is reported as medium`() {
        val alert = decide(facts("com.example.app", Capability.ACCESSIBILITY))
        assertEquals(Severity.MEDIUM, alert!!.severity)
    }

    @Test
    fun `overlay plus notification access is high and ordered`() {
        val alert = decide(
            facts(
                "com.example.app",
                Capability.NOTIFICATION_ACCESS,
                Capability.OVERLAY
            )
        )
        assertNotNull(alert)
        assertEquals(Severity.HIGH, alert!!.severity)
        // Порядок объявления, а не порядок в наборе: в тексте уведомления сначала главное право.
        assertEquals(listOf(Capability.OVERLAY, Capability.NOTIFICATION_ACCESS), alert.capabilities)
    }

    @Test
    fun `alert carries label and protected count`() {
        val protected = setOf("com.bank.app", "com.mail.app")
        val alert = decide(facts("com.example.app", Capability.OVERLAY, label = "Фонарик"), protected)
        assertEquals("Фонарик", alert!!.displayName)
        assertEquals(2, alert.protectedCount)
        assertEquals("com.example.app", alert.packageName)
    }

    @Test
    fun `blank label falls back to package name`() {
        val alert = decide(facts("com.example.app", Capability.OVERLAY, label = "   "))
        assertEquals("com.example.app", alert!!.displayName)
    }

    @Test
    fun `repeat within a day is suppressed`() {
        val alert = decide(
            facts("com.example.app", Capability.OVERLAY),
            lastAlertAt = now - NeighbourAlertPolicy.REPEAT_WINDOW_MS / 2
        )
        assertNull(alert)
    }

    @Test
    fun `repeat exactly at the end of the window is allowed`() {
        val alert = decide(
            facts("com.example.app", Capability.OVERLAY),
            lastAlertAt = now - NeighbourAlertPolicy.REPEAT_WINDOW_MS
        )
        assertNotNull(alert)
    }

    @Test
    fun `stored alert is read back for the same package`() {
        // Связь «решение → хранилище»: иначе окно повтора существовало бы только в теории.
        val stored = NeighbourAlertPolicy.remember(
            alerts = emptyMap(),
            packageName = "com.example.app",
            atMillis = now - 1000
        )
        val parsed = NeighbourAlertPolicy.parse(NeighbourAlertPolicy.format(stored), now)
        assertEquals(now - 1000, parsed["com.example.app"])
        assertNull(decide(facts("com.example.app", Capability.OVERLAY), lastAlertAt = parsed["com.example.app"]))
    }

    @Test
    fun `malformed records are ignored`() {
        val parsed = NeighbourAlertPolicy.parse(
            setOf(
                "без разделителя",
                "=нет пакета",
                "com.example.app=",
                "com.example.app=не число",
                "com.example.app=0",
                "com.example.app=${now - 1000}"
            ),
            now
        )
        assertEquals(1, parsed.size)
        assertEquals(now - 1000, parsed["com.example.app"])
    }

    @Test
    fun `expired records are dropped`() {
        val parsed = NeighbourAlertPolicy.parse(
            setOf(
                "old.app=${now - NeighbourAlertPolicy.REPEAT_WINDOW_MS - 1}",
                "fresh.app=${now - 1000}"
            ),
            now
        )
        assertEquals(setOf("fresh.app"), parsed.keys)
    }

    @Test
    fun `records are capped and the newest survive`() {
        var alerts = emptyMap<String, Long>()
        repeat(NeighbourAlertPolicy.MAX_REMEMBERED + 5) { index ->
            alerts = NeighbourAlertPolicy.remember(alerts, "com.example.app$index", now - index)
        }
        assertEquals(NeighbourAlertPolicy.MAX_REMEMBERED, alerts.size)
        // Самые свежие — с наименьшим «сколько назад», они и должны остаться.
        assertTrue("com.example.app0" in alerts)
        assertTrue("com.example.app${NeighbourAlertPolicy.MAX_REMEMBERED + 4}" !in alerts)
    }

    @Test
    fun `blank package is not remembered`() {
        val alerts = NeighbourAlertPolicy.format(
            NeighbourAlertPolicy.remember(emptyMap(), "  ", now)
        )
        assertTrue(alerts.isEmpty())
    }
}
