package com.phoneguard.firewall

import android.content.pm.PackageManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import com.phoneguard.model.blocksAllTraffic
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кэш правил фаервола и соответствия UID → пакеты.
 *
 * Исправления:
 *  - раньше `uidPackageCache` хранил **одно** имя пакета на UID, поэтому у
 *    приложений с общим `sharedUserId` (и у пакетов, у которых UID совпадает с
 *    другим приложением) правило одного приложения применялось к другому.
 *    Теперь на UID хранится набор пакетов;
 *  - добавлен [blockedPackages], который считает список приложений, трафик
 *    которых нужно заблокировать прямо сейчас (с учётом типа сети) — на нём
 *    построена работа [PhoneGuardVpnService].
 */
@Singleton
class FirewallRulesManager @Inject constructor(
    private val firewallRepository: FirewallRepository
) {

    /**
     * Кэши — «снимки», которые заменяются целиком.
     *
     * `loadRules()`/`loadInstalledApps()` вызываются из IO-корутины сервиса, а читаются
     * они одновременно из потока VPN (`handlePacket`) и из UI. Раньше это были
     * изменяемые `HashMap`, которые чистились и наполнялись на месте: параллельное
     * чтение могло увидеть пустой кэш (трафик на миг «разблокировался») или упасть с
     * `ConcurrentModificationException`. Теперь новые значения публикуются одной
     * ссылкой, а читатели видят либо старый, либо новый полный набор.
     */
    @Volatile
    private var index: FirewallRuleIndex = FirewallRuleIndex()

    suspend fun loadRules() {
        val rules = firewallRepository.allRules.first()
        index = index.withRules(rules)
    }

    fun loadInstalledApps(packageManager: PackageManager) {
        val installedApps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        index = index.withUidPackages(
            FirewallRuleIndex.groupByUid(installedApps.map { app -> app.uid to app.packageName })
        )
    }

    fun getRuleForPackage(packageName: String): FirewallRule? = index.ruleFor(packageName)

    /** Все пакеты, работающие под указанным UID (у приложений бывает общий UID). */
    fun getPackageNamesForUid(uid: Int): List<String> = index.packagesForUid(uid)

    /** Первый пакет UID — для логов и событий вторжения. */
    fun getPackageNameForUid(uid: Int): String? = index.firstPackageForUid(uid)

    fun getRulesSnapshot(): Map<String, FirewallRule> = index.snapshot()

    /**
     * Пакеты, которые нужно полностью заблокировать в текущих условиях.
     *
     * Полная блокировка означает «весь трафик приложения», поэтому такое
     * приложение направляется в TUN-интерфейс и его пакеты просто отбрасываются.
     * Приложения, которым блокировка не нужна, в туннель не попадают вовсе и
     * работают через обычную сеть.
     */
    fun blockedPackages(isOnWifi: Boolean): Set<String> = index.blockedPackages(isOnWifi)

    /**
     * Возвращает true, если правило блокирует весь трафик приложения.
     *
     * Сама матрица живёт в чистой [blocksAllTraffic] (`model/FirewallRulePolicy.kt`), чтобы её
     * можно было прогонять тестами без Android: здесь только точка входа для сервиса.
     */
    fun shouldBlockAllTraffic(rule: FirewallRule, isOnWifi: Boolean): Boolean =
        rule.blocksAllTraffic(isOnWifi)
}
