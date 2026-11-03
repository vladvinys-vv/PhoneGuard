package com.phoneguard.fullscan.checks

/**
 * Как из вердикта [PermissionRiskPolicy] получается текст находки.
 *
 * В проверке ([PermissionAbuseCheck]) остались только `Context`, `PackageManager` и `R.string`,
 * а выбор шаблона и — главное — **какие группы разрешений попадают в текст** вынесены сюда.
 * Причина: раньше одна и та же пара «чувствительных» разрешений подставляла в объяснение то
 * `matched`, то все выданные группы, и заметить это можно было только глазами в отчёте.
 */
internal object PermissionAbusePolicy {

    /** Шаблон строки объяснения (сопоставляется с `R.string` в проверке). */
    enum class Template {
        CRITICAL_COMBO,
        SMS_EXTRA,
        BACKGROUND_LOCATION,
        MANY_SENSITIVE
    }

    /** Какие группы подставляются в шаблон. */
    enum class Scope {
        /** Только те группы, из-за которых сработало правило. */
        MATCHED,

        /** Все выданные группы. */
        ALL,

        /** Шаблон без подстановок. */
        NONE
    }

    data class Plan(val template: Template, val scope: Scope)

    fun plan(reason: PermissionRiskPolicy.Reason): Plan = when (reason) {
        // Критическая комбинация: важно показать именно её, а не весь список разрешений.
        PermissionRiskPolicy.Reason.CRITICAL_COMBO -> Plan(Template.CRITICAL_COMBO, Scope.MATCHED)
        // «СМС + прочее» — смысл находки в связке, поэтому показываем всё выданное.
        PermissionRiskPolicy.Reason.SMS_PLUS_OTHER -> Plan(Template.SMS_EXTRA, Scope.ALL)
        // Фоновая геолокация говорит сама за себя — подстановок нет.
        PermissionRiskPolicy.Reason.BACKGROUND_LOCATION -> Plan(Template.BACKGROUND_LOCATION, Scope.NONE)
        // Пара «опасных» разрешений: показываем именно пару, иначе объяснение не сходится с риском.
        PermissionRiskPolicy.Reason.SENSITIVE_PAIR -> Plan(Template.MANY_SENSITIVE, Scope.MATCHED)
        PermissionRiskPolicy.Reason.MANY_SENSITIVE -> Plan(Template.MANY_SENSITIVE, Scope.ALL)
    }
}
