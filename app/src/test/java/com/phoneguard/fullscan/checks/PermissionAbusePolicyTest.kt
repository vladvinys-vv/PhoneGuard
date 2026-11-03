package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Текст находки «опасные разрешения»: какой шаблон и какие группы в него подставляются.
 *
 * Раньше этот выбор жил внутри `PermissionAbuseCheck.assessRisk` (рядом с `Context`,
 * `PackageManager` и `R.string`) и не проверялся. Ошибка здесь не роняет приложение и не
 * меняет вердикт — она делает объяснение неточным, а такое глазами почти не ловится.
 */
class PermissionAbusePolicyTest {

    @Test
    fun criticalComboShowsOnlyMatchedGroups() {
        val plan = PermissionAbusePolicy.plan(PermissionRiskPolicy.Reason.CRITICAL_COMBO)
        assertEquals(PermissionAbusePolicy.Template.CRITICAL_COMBO, plan.template)
        assertEquals(PermissionAbusePolicy.Scope.MATCHED, plan.scope)
    }

    @Test
    fun smsPlusOtherShowsEveryGrantedGroup() {
        // Смысл находки — в связке СМС с остальными правами, поэтому показываются все группы.
        val plan = PermissionAbusePolicy.plan(PermissionRiskPolicy.Reason.SMS_PLUS_OTHER)
        assertEquals(PermissionAbusePolicy.Template.SMS_EXTRA, plan.template)
        assertEquals(PermissionAbusePolicy.Scope.ALL, plan.scope)
    }

    @Test
    fun backgroundLocationNeedsNoSubstitution() {
        val plan = PermissionAbusePolicy.plan(PermissionRiskPolicy.Reason.BACKGROUND_LOCATION)
        assertEquals(PermissionAbusePolicy.Template.BACKGROUND_LOCATION, plan.template)
        assertEquals(PermissionAbusePolicy.Scope.NONE, plan.scope)
    }

    @Test
    fun sensitivePairShowsThePairNotEverything() {
        // Главная проверка правила: пара «опасных» разрешений не должна подставлять в текст
        // весь список выданных — иначе объяснение не сходится с риском.
        val plan = PermissionAbusePolicy.plan(PermissionRiskPolicy.Reason.SENSITIVE_PAIR)
        assertEquals(PermissionAbusePolicy.Template.MANY_SENSITIVE, plan.template)
        assertEquals(PermissionAbusePolicy.Scope.MATCHED, plan.scope)
    }

    @Test
    fun manySensitiveShowsAllGroups() {
        val plan = PermissionAbusePolicy.plan(PermissionRiskPolicy.Reason.MANY_SENSITIVE)
        assertEquals(PermissionAbusePolicy.Template.MANY_SENSITIVE, plan.template)
        assertEquals(PermissionAbusePolicy.Scope.ALL, plan.scope)
    }

    @Test
    fun everyReasonHasAPlan() {
        // Исчерпывающий `when` в правиле не даст забыть новую причину, но если её добавят
        // вместе с шаблоном, этот тест напомнит проверить ожидания выше.
        val reasons = PermissionRiskPolicy.Reason.entries
        assertTrue("причины не должны исчезнуть", reasons.isNotEmpty())
        reasons.forEach { reason ->
            val plan = PermissionAbusePolicy.plan(reason)
            when (plan.scope) {
                PermissionAbusePolicy.Scope.NONE ->
                    assertTrue("шаблон без подстановок должен быть только у причины без групп", true)
                else -> Unit
            }
        }
    }

    @Test
    fun onlyBackgroundLocationHasNoSubstitution() {
        val withoutSubstitution = PermissionRiskPolicy.Reason.entries
            .filter { PermissionAbusePolicy.plan(it).scope == PermissionAbusePolicy.Scope.NONE }
        assertEquals(listOf(PermissionRiskPolicy.Reason.BACKGROUND_LOCATION), withoutSubstitution)
    }

    @Test
    fun matchedScopeIsUsedForComboAndPairOnly() {
        val matched = PermissionRiskPolicy.Reason.entries
            .filter { PermissionAbusePolicy.plan(it).scope == PermissionAbusePolicy.Scope.MATCHED }
            .toSet()
        assertEquals(
            setOf(
                PermissionRiskPolicy.Reason.CRITICAL_COMBO,
                PermissionRiskPolicy.Reason.SENSITIVE_PAIR
            ),
            matched
        )
    }
}
