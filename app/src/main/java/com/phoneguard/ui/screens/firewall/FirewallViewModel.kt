package com.phoneguard.ui.screens.firewall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.firewall.PhoneGuardVpnService
import com.phoneguard.model.FirewallRule
import com.phoneguard.util.InstalledAppsCache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class FirewallStats(
    val todayBlocks: Int = 0,
    val topBlockedPackage: String? = null
)

@HiltViewModel
class FirewallViewModel @Inject constructor(
    private val repository: FirewallRepository,
    @ApplicationContext private val context: Context,
    private val installedAppsCache: InstalledAppsCache
) : ViewModel() {

    val allRules = repository.allRules
    val allLogs = repository.allLogs

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val _logsPage = MutableStateFlow<List<com.phoneguard.model.FirewallLog>>(emptyList())
    val logsPage: StateFlow<List<com.phoneguard.model.FirewallLog>> = _logsPage.asStateFlow()

    private val _hasMoreLogs = MutableStateFlow(false)
    val hasMoreLogs: StateFlow<Boolean> = _hasMoreLogs.asStateFlow()

    private val _stats = MutableStateFlow(FirewallStats())
    val stats: StateFlow<FirewallStats> = _stats.asStateFlow()

    private val logPageSize = 20
    private val logOffset = MutableStateFlow(0)

    init {
        loadApps()
        loadLogs()
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            val today = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            val logs = repository.getAllLogs().first()
            val todayBlocks = logs.count { it.timestamp > today }
            val topPackage = logs.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }?.key
            _stats.value = FirewallStats(
                todayBlocks = todayBlocks,
                topBlockedPackage = topPackage
            )
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val installedApps = installedAppsCache.getInstalledApps()
                val pm = context.packageManager
                installedApps
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { appInfo ->
                        val rule = repository.getRuleForPackage(appInfo.packageName)
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName = pm.getApplicationLabel(appInfo).toString(),
                            icon = pm.getApplicationIcon(appInfo),
                            rule = rule
                        )
                    }
                    .sortedBy { it.appName }
            }
            _apps.value = apps
        }
    }

    private fun loadLogs() {
        viewModelScope.launch {
            val logs = repository.getPagedLogs(logPageSize, logOffset.value)
            val total = repository.getLogsCount()
            _logsPage.value = logs
            _hasMoreLogs.value = (logOffset.value + logs.size) < total
        }
    }

    fun loadMoreLogs() {
        if (!_hasMoreLogs.value) return
        logOffset.value += logPageSize
        loadLogs()
    }

    fun upsertRule(rule: FirewallRule) {
        viewModelScope.launch {
            repository.upsertRule(rule)
            loadApps()
        }
    }

    /** Возвращает Intent для запроса VPN-разрешения. null — разрешение уже есть. */
    fun prepareVpn(): Intent? {
        return VpnService.prepare(context)
    }

    fun onVpnPrepared() {
        launchVpnService()
    }

    fun onVpnPrepareResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            launchVpnService()
        }
    }

    private fun launchVpnService() {
        val intent = Intent(context, PhoneGuardVpnService::class.java).apply {
            action = PhoneGuardVpnService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _isVpnActive.value = true
    }

    fun stopVpn() {
        val intent = Intent(context, PhoneGuardVpnService::class.java).apply {
            action = PhoneGuardVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
        _isVpnActive.value = false
    }

    /**
     * Возвращает UID для обновления правил – вызывается из `handlePacket`.
     * На API 29+ используем ConnectivityManager.getConnectionOwnerUid().
     */
    fun getAppUidForConnection(destIp: String, destPort: Int, protocol: Int): Int? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
                // Пытаемся определить UID через сокетную информацию
                InetAddress.getByName(destIp)?.let { address ->
                    val network = cm.activeNetwork
                    val caps = cm.getNetworkCapabilities(network)
                    val ownerUid = cm.getConnectionOwnerUid(
                        address,
                        destPort,
                        protocol,
                        null, 0
                    )
                    if (ownerUid > 0) ownerUid else null
                }
            } else null
        } catch (_: Exception) { null }
    }
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable,
    val rule: FirewallRule?
)
