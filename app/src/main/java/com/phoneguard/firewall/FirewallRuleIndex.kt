package com.phoneguard.firewall

import com.phoneguard.model.FirewallRule
import com.phoneguard.model.blocksAllTraffic

/**
 * Неизменяемый снимок правил фаервола и соответствия UID → пакеты.
 *
 * Раньше эта логика жила прямо в [FirewallRulesManager] рядом с `Flow` из Room и
 * `PackageManager`, поэтому тесты фаервола не исполнялись в JVM (MockK + корутины) — матрица
 * «кого сейчас блокировать» на деле не проверялась ни разу. Здесь только чистые данные:
 * менеджер собирает снимок и публикует его одной ссылкой (`@Volatile`), читатели видят либо
 * старый полный набор, либо новый.
 *
 * Матрица «правило блокирует весь трафик» остаётся в [blocksAllTraffic] (`model/FirewallRulePolicy.kt`)
 * — здесь только выборка по ней.
 */
internal class FirewallRuleIndex(
    rules: List<FirewallRule> = emptyList(),
    private val uidPackages: Map<Int, Set<String>> = emptyMap()
) {

    private val byPackage: Map<String, FirewallRule> = rules.associateBy { it.packageName }

    /** Снимок `packageName → правило` (копия: читатель не может испортить кэш). */
    fun snapshot(): Map<String, FirewallRule> = HashMap(byPackage)

    fun ruleFor(packageName: String): FirewallRule? = byPackage[packageName]

    /**
     * Все пакеты под UID.
     *
     * У приложений бывает общий UID (`sharedUserId`), поэтому на один UID может приходиться
     * несколько пакетов — раньше хранился один, и правило одного приложения применялось к другому.
     */
    fun packagesForUid(uid: Int): List<String> = uidPackages[uid]?.toList() ?: emptyList()

    /** Первый пакет UID — для логов и событий вторжения. */
    fun firstPackageForUid(uid: Int): String? = uidPackages[uid]?.firstOrNull()

    /** Пакеты, которые нужно полностью заблокировать в текущих условиях. */
    fun blockedPackages(isOnWifi: Boolean): Set<String> = byPackage.values
        .asSequence()
        .filter { rule -> rule.blocksAllTraffic(isOnWifi) }
        .map { rule -> rule.packageName }
        .toSet()

    /** Новый снимок с другими правилами; соответствие UID → пакеты сохраняется. */
    fun withRules(rules: List<FirewallRule>): FirewallRuleIndex =
        FirewallRuleIndex(rules, uidPackages)

    /** Новый снимок с другим соответствием UID → пакеты; правила сохраняются. */
    fun withUidPackages(uidPackages: Map<Int, Set<String>>): FirewallRuleIndex =
        FirewallRuleIndex(byPackage.values.toList(), uidPackages)

    companion object {
        /**
         * Группирует установленные приложения по UID.
         *
         * Вынесено отдельно от `PackageManager`, чтобы проверять группировку без Android:
         * на вход идёт список пар `uid to packageName`.
         */
        fun groupByUid(installed: List<Pair<Int, String>>): Map<Int, Set<String>> =
            installed
                .groupBy({ (uid, _) -> uid }, { (_, packageName) -> packageName })
                .mapValues { (_, packages) -> packages.toSet() }
    }
}
