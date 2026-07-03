package com.phoneguard.data.repository

import com.phoneguard.data.local.FirewallDao
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.FirewallRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirewallRepository @Inject constructor(
    private val firewallDao: FirewallDao
) {
    val allRules: Flow<List<FirewallRule>> = firewallDao.getAllRules()
    val allLogs: Flow<List<FirewallLog>> = firewallDao.getAllLogs()

    suspend fun getRuleForPackage(packageName: String): FirewallRule? =
        withContext(Dispatchers.IO) {
            firewallDao.getRuleForPackage(packageName)
        }

    suspend fun upsertRule(rule: FirewallRule) = withContext(Dispatchers.IO) {
        firewallDao.upsertRule(rule)
    }

    suspend fun deleteRule(rule: FirewallRule) = withContext(Dispatchers.IO) {
        firewallDao.deleteRule(rule)
    }

    suspend fun insertLog(log: FirewallLog) = withContext(Dispatchers.IO) {
        firewallDao.insertLog(log)
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        firewallDao.clearLogs()
    }
}
