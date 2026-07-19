package com.phoneguard.firewall

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirewallRulesManager @Inject constructor(
    private val firewallRepository: FirewallRepository
) {

    private val rulesCache = mutableMapOf<String, FirewallRule>()
    private val uidPackageCache = mutableMapOf<Int, String>()

    suspend fun loadRules() {
        val rules = firewallRepository.allRules.first()
        rulesCache.clear()
        rules.forEach { rulesCache[it.packageName] = it }
    }

    fun loadInstalledApps(packageManager: PackageManager) {
        uidPackageCache.clear()
        val installedApps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        installedApps.forEach { app ->
            uidPackageCache[app.uid] = app.packageName
        }
    }

    fun getRuleForPackage(packageName: String): FirewallRule? = rulesCache[packageName]

    fun getPackageNameForUid(uid: Int): String? = uidPackageCache[uid]

    fun getRulesSnapshot(): Map<String, FirewallRule> = HashMap(rulesCache)
}
