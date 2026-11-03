package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Почему включённая служба доступности попала в отчёт.
 *
 * Порядок причин решает текст, который видит пользователь. Раньше это был `when` внутри
 * `AccessibilityServiceCheck` за `Settings.Secure`, `PackageManager` и `R.string` — то есть
 * не проверялся вовсе. Приоритет намеренный: «подозрительное имя» полезнее, чем «приложение
 * удалено», даже если верно и то, и другое.
 */
class AccessibilityRiskPolicyTest {

    @Test
    fun suspiciousPatternWins() {
        assertEquals(
            AccessibilityRiskReason.SUSPICIOUS_NAME,
            AccessibilityRiskPolicy.reasonFor("keylog", "Banking")
        )
    }

    @Test
    fun suspiciousPatternWinsEvenForRemovedApp() {
        // Удалённый spyware остаётся spyware: пользователю важнее узнать про перехват,
        // чем про то, что пакета уже нет.
        assertEquals(
            AccessibilityRiskReason.SUSPICIOUS_NAME,
            AccessibilityRiskPolicy.reasonFor("autoclick", null)
        )
    }

    @Test
    fun missingLabelMeansRemovedApp() {
        assertEquals(
            AccessibilityRiskReason.REMOVED_APP,
            AccessibilityRiskPolicy.reasonFor(null, null)
        )
    }

    @Test
    fun ordinaryThirdPartyServiceStaysThirdParty() {
        assertEquals(
            AccessibilityRiskReason.THIRD_PARTY,
            AccessibilityRiskPolicy.reasonFor(null, "Some Access Service")
        )
    }

    @Test
    fun emptyPatternIsNotAPattern() {
        // `AccessibilityHeuristics.matchingPattern` возвращает `null`, когда совпадений нет;
        // пустая строка сюда попасть не должна, но правило не должно её «подозревать».
        assertEquals(
            AccessibilityRiskReason.SUSPICIOUS_NAME,
            AccessibilityRiskPolicy.reasonFor("", "App")
        )
        assertEquals(AccessibilityRiskReason.REMOVED_APP, AccessibilityRiskPolicy.reasonFor(null, null))
    }

    @Test
    fun reasonIsExactlyOneOfThree() {
        // Перечисление причин — часть контракта текста находки: добавили причину, значит
        // нужен и `R.string` для неё (проверка маппинга живёт в AccessibilityServiceCheck).
        assertEquals(3, AccessibilityRiskReason.entries.size)
    }
}
