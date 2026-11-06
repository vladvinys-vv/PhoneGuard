package com.phoneguard.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила «защиты приложений»: кого предлагать, кого считать опасным и что делать со списком.
 *
 * Здесь важны именно отказы и границы: подсказка не должна предлагать системные приложения,
 * защищённое приложение не может быть угрозой само себе, а предел списка обязан
 * останавливать добавление — иначе интерфейс молча теряет нажатие.
 */
class SensitiveAppPolicyTest {

    private fun app(
        packageName: String,
        label: String = "",
        isSystem: Boolean = false,
        vararg capabilities: SensitiveAppPolicy.Capability
    ) = SensitiveAppPolicy.AppFacts(packageName, label, isSystem, capabilities.toSet())

    @Test
    fun `banking apps are suggested`() {
        assertTrue(SensitiveAppPolicy.looksSensitive("de.commerzbank.mobile", "Commerzbank"))
        assertTrue(SensitiveAppPolicy.looksSensitive("de.sparkasse.push", "Sparkasse"))
        assertTrue(SensitiveAppPolicy.looksSensitive("com.n26", "N26"))
        assertTrue(SensitiveAppPolicy.looksSensitive("de.dkb.app", "DKB"))
    }

    @Test
    fun `package name alone is enough`() {
        // Подпись бывает пустой (локализация, кастомный лаунчер) — пакет остаётся.
        assertTrue(SensitiveAppPolicy.looksSensitive("org.telegram.messenger", ""))
    }

    @Test
    fun `passwords mail and wallets are recognised`() {
        assertTrue(SensitiveAppPolicy.looksSensitive("com.bitwarden.mobile", "Bitwarden"))
        assertTrue(SensitiveAppPolicy.looksSensitive("com.microsoft.office.outlook", "Outlook"))
        assertTrue(SensitiveAppPolicy.looksSensitive("com.coinbase.android", "Coinbase"))
    }

    @Test
    fun `ordinary app is not sensitive`() {
        assertFalse(SensitiveAppPolicy.looksSensitive("com.example.torch", "Taschenlampe"))
        assertFalse(SensitiveAppPolicy.looksSensitive("", ""))
    }

    @Test
    fun `capability combination decides severity`() {
        assertEquals(
            SensitiveAppPolicy.Severity.HIGH,
            SensitiveAppPolicy.severityOf(
                setOf(
                    SensitiveAppPolicy.Capability.OVERLAY,
                    SensitiveAppPolicy.Capability.ACCESSIBILITY
                )
            )
        )
        // Оверлей + уведомления — это подмена экрана с чтением кодов подтверждения.
        assertEquals(
            SensitiveAppPolicy.Severity.HIGH,
            SensitiveAppPolicy.severityOf(
                setOf(
                    SensitiveAppPolicy.Capability.OVERLAY,
                    SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS
                )
            )
        )
        assertEquals(
            SensitiveAppPolicy.Severity.MEDIUM,
            SensitiveAppPolicy.severityOf(setOf(SensitiveAppPolicy.Capability.OVERLAY))
        )
        assertEquals(
            SensitiveAppPolicy.Severity.MEDIUM,
            SensitiveAppPolicy.severityOf(setOf(SensitiveAppPolicy.Capability.ACCESSIBILITY))
        )
        assertEquals(
            SensitiveAppPolicy.Severity.MEDIUM,
            SensitiveAppPolicy.severityOf(setOf(SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS))
        )
        assertEquals(
            SensitiveAppPolicy.Severity.LOW,
            SensitiveAppPolicy.severityOf(setOf(SensitiveAppPolicy.Capability.DEVICE_ADMIN))
        )
        assertNull(SensitiveAppPolicy.severityOf(emptySet()))
    }

    @Test
    fun `system apps are never exposures`() {
        val apps = listOf(
            app("com.android.systemui", "System UI", isSystem = true, SensitiveAppPolicy.Capability.OVERLAY)
        )
        assertTrue(SensitiveAppPolicy.exposures(emptySet(), apps).isEmpty())
    }

    @Test
    fun `protected app is not an exposure by itself`() {
        val apps = listOf(
            app("com.mybank", "My Bank", false, SensitiveAppPolicy.Capability.OVERLAY)
        )
        assertTrue(SensitiveAppPolicy.exposures(setOf("com.mybank"), apps).isEmpty())
    }

    @Test
    fun `apps without capabilities are not exposures`() {
        val apps = listOf(app("com.example.torch", "Taschenlampe"))
        assertTrue(SensitiveAppPolicy.exposures(emptySet(), apps).isEmpty())
    }

    @Test
    fun `exposures are sorted from the most dangerous`() {
        val apps = listOf(
            app("com.a.admin", "A Admin", false, SensitiveAppPolicy.Capability.DEVICE_ADMIN),
            app("com.b.overlay", "B Overlay", false, SensitiveAppPolicy.Capability.OVERLAY),
            app(
                "com.c.both", "C Both", false,
                SensitiveAppPolicy.Capability.OVERLAY,
                SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS
            )
        )
        val ordered = SensitiveAppPolicy.exposures(emptySet(), apps).map { it.packageName }
        assertEquals(listOf("com.c.both", "com.b.overlay", "com.a.admin"), ordered)
    }

    @Test
    fun `exposure lists capabilities in declaration order`() {
        val apps = listOf(
            app(
                "com.c.both", "C Both", false,
                SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS,
                SensitiveAppPolicy.Capability.OVERLAY
            )
        )
        val exposure = SensitiveAppPolicy.exposures(emptySet(), apps).single()
        // В интерфейсе первым идёт самое заметное право, а не порядок прихода из набора.
        assertEquals(
            listOf(
                SensitiveAppPolicy.Capability.OVERLAY,
                SensitiveAppPolicy.Capability.NOTIFICATION_ACCESS
            ),
            exposure.capabilities
        )
    }

    @Test
    fun `suggestions skip system and already protected apps`() {
        val apps = listOf(
            app("de.sparkasse", "Sparkasse"),
            app("de.sparkasse.system", "Sparkasse intern", isSystem = true),
            app("org.telegram.messenger", "Telegram")
        )
        val suggestions = SensitiveAppPolicy.suggestions(apps, setOf("org.telegram.messenger"))
        assertEquals(listOf("de.sparkasse"), suggestions.map { it.packageName })
    }

    @Test
    fun `suggestions are sorted by name`() {
        val apps = listOf(
            app("org.telegram.messenger", "Telegram"),
            app("de.dkb.app", "DKB")
        )
        assertEquals(
            listOf("DKB", "Telegram"),
            SensitiveAppPolicy.suggestions(apps, emptySet()).map { it.displayName }
        )
    }

    @Test
    fun `uninstalled protected app keeps its place`() {
        val apps = listOf(app("de.sparkasse", "Sparkasse"))
        val protectedApps = SensitiveAppPolicy.protectedApps(
            setOf("de.sparkasse", "com.gone.app"),
            apps
        )
        assertEquals(listOf("com.gone.app", "de.sparkasse"), protectedApps.map { it.packageName })
        // Подписи нет — показываем пакет, но не пустую строку.
        assertEquals("com.gone.app", protectedApps.first().displayName)
    }

    @Test
    fun `blank label falls back to package name`() {
        assertEquals("com.example.torch", app("com.example.torch").displayName)
    }

    @Test
    fun `toggle adds and removes`() {
        assertEquals(setOf("a"), SensitiveAppPolicy.toggle(emptySet(), "a", true))
        assertEquals(emptySet<String>(), SensitiveAppPolicy.toggle(setOf("a"), "a", false))
        // Повторное добавление ничего не дублирует.
        assertEquals(setOf("a"), SensitiveAppPolicy.toggle(setOf("a"), "a", true))
        // Пустой пакет — не приложение.
        assertEquals(emptySet<String>(), SensitiveAppPolicy.toggle(emptySet(), "  ", true))
    }

    @Test
    fun `hidden exposures are not shown`() {
        // «Больше не предупреждать» — не «не опасно»: приложение остаётся в найденном, но
        // перестаёт занимать экран. Иначе список находок превращается в навязчивый.
        val apps = listOf(
            SensitiveAppPolicy.AppFacts("spy.app", "Spy", isSystem = false, capabilities = setOf(SensitiveAppPolicy.Capability.OVERLAY)),
            SensitiveAppPolicy.AppFacts("tool.app", "Tool", isSystem = false, capabilities = setOf(SensitiveAppPolicy.Capability.ACCESSIBILITY))
        )
        val protected = setOf("bank.app")
        val shown = SensitiveAppPolicy.exposures(protected, apps, hidden = setOf("spy.app"))
        assertEquals(listOf("tool.app"), shown.map { it.packageName })
        assertEquals(2, SensitiveAppPolicy.exposures(protected, apps).size)
    }

    @Test
    fun `hiding everything gives an empty list, not a broken one`() {
        val apps = listOf(
            SensitiveAppPolicy.AppFacts("spy.app", "Spy", isSystem = false, capabilities = setOf(SensitiveAppPolicy.Capability.OVERLAY))
        )
        assertTrue(SensitiveAppPolicy.exposures(setOf("bank.app"), apps, hidden = setOf("spy.app")).isEmpty())
    }

    @Test
    fun `hidden app stays in suggestions if it looks sensitive`() {
        // Скрытие касается только списка опасных соседей: предложение «добавить в защиту»
        // от него не зависит, иначе банк, скрытый как сосед, выпал бы из предложений.
        val apps = listOf(
            SensitiveAppPolicy.AppFacts("com.bank.app", "Bank", isSystem = false, capabilities = setOf(SensitiveAppPolicy.Capability.OVERLAY))
        )
        val suggestions = SensitiveAppPolicy.suggestions(apps, protected = emptySet())
        assertEquals(listOf("com.bank.app"), suggestions.map { it.packageName })
    }

    @Test
    fun `limit stops adding but never blocks removing`() {
        val full = (1..SensitiveAppPolicy.MAX_PROTECTED).map { "pkg$it" }.toSet()
        assertTrue(SensitiveAppPolicy.isAtLimit(full))
        assertEquals(full, SensitiveAppPolicy.toggle(full, "new.app", true))
        assertEquals(
            full - "pkg1",
            SensitiveAppPolicy.toggle(full, "pkg1", false)
        )
        assertFalse(SensitiveAppPolicy.isAtLimit(full - "pkg1"))
    }
}
