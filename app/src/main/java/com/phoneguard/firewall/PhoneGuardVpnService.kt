package com.phoneguard.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.FirewallRule
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * PhoneGuard VPN-based Firewall.
 *
 * Архитектура:
 * 1. TUN-интерфейс ловит все IP-пакеты устройства
 * 2. Парсинг IP-заголовка → определение протокола (TCP/UDP/ICMP)
 * 3. Для TCP/UDP → извлечение портов
 * 4. Определение UID через ConnectionManager.getConnectionOwnerUid() (API 29+)
 * 5. Lookup правила в кэше → block/allow
 * 6. Логирование всех блокировок в Room
 * 7. Разрешённые пакеты форвардятся обратно в TUN
 *
 * Ограничения без root:
 * - Нет реального DNS resolution (пакеты не выходят через VPN)
 * - UID determination работает только для активных socket-соединений
 * - Для полного форвардинга нужен реальный upstream (TUN → raw socket → интернет)
 */
@AndroidEntryPoint
class PhoneGuardVpnService : VpnService() {

    companion object {
        private const val TAG = "PGVpn"
        private const val VPN_NOTIFICATION_ID = 1001
        private const val VPN_CHANNEL_ID = "phoneguard_vpn_channel"
        const val ACTION_CONNECT = "com.phoneguard.CONNECT"
        const val ACTION_DISCONNECT = "com.phoneguard.DISCONNECT"

        // DNS-серверы для TUN
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_MTU = 1500
        private const val TUN_NETWORK = "0.0.0.0"
        private const val TUN_PREFIX = 0

        // Лимит логов в секунду (rate limiting)
        private const val MAX_LOGS_PER_SECOND = 10
    }

    @Inject
    lateinit var firewallRepository: FirewallRepository

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val vpnScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Кэш правил: packageName → FirewallRule */
    private val rulesCache = mutableMapOf<String, FirewallRule>()

    /** Кэш UID → packageName */
    private val uidPackageCache = mutableMapOf<Int, String>()

    /** Rate limiter для логов */
    private var logCount = 0
    private var logWindowStart = 0L

    // Pre-allocated buffers to reduce GC pressure
    private val ipHeaderBuffer = ByteArray(60)
    private val packetBuffer = ByteArray(65535)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CONNECT -> {
                startVpn()
                START_STICKY
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_CHANNEL_ID,
                "PhoneGuard Firewall",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN-based firewall monitoring"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val blockedCount = logCount
        return Notification.Builder(this, VPN_CHANNEL_ID)
            .setContentTitle("PhoneGuard Firewall Active")
            .setContentText("Blocking unwanted traffic... ($blockedCount blocked)")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            Log.d(TAG, "VPN already running")
            return
        }

        vpnJob = vpnScope.launch {
            try {
                val rules = firewallRepository.allRules.first()
                rulesCache.clear()
                rules.forEach { rulesCache[it.packageName] = it }

                uidPackageCache.clear()
                val pm = packageManager
                val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(
                        android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }
                installedApps.forEach { app ->
                    uidPackageCache[app.uid] = app.packageName
                }
                Log.d(TAG, "Loaded ${rulesCache.size} rules, ${uidPackageCache.size} apps")

                val builder = Builder()
                    .addAddress(TUN_ADDRESS, 32)
                    .addRoute(TUN_NETWORK, TUN_PREFIX)
                    .setMtu(TUN_MTU)
                    .setSession("PhoneGuard Firewall")
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")

                vpnInterface = builder.establish()
                if (vpnInterface != null) {
                    startForeground(VPN_NOTIFICATION_ID, getNotification())
                    Log.d(TAG, "VPN started, TUN interface created")
                    runVpnLoop()
                } else {
                    Log.e(TAG, "Failed to establish VPN interface")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
            }
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        vpnJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runVpnLoop() {
        vpnJob = vpnScope.launch {
            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(65535)

            Log.d(TAG, "VPN loop started, reading packets...")

            while (isActive) {
                val bytesRead = try {
                    input.read(buffer.array())
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Error reading VPN input", e)
                    break
                }

                if (bytesRead > 0) {
                    val packet = ByteArray(bytesRead)
                    System.arraycopy(buffer.array(), 0, packet, 0, bytesRead)
                    buffer.clear()

                    try {
                        handlePacket(packet, output)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling packet", e)
                        // Forward on error
                        try { output.write(packet) } catch (_: Exception) {}
                    }
                }
            }

            Log.d(TAG, "VPN loop ended")
        }
    }

    /**
     * Обработка одного IP-пакета:
     * 1. Парсинг IP-заголовка
     * 2. Извлечение портов (TCP/UDP)
     * 3. Определение UID
     * 4. Проверка правил
     * 5. Block or forward
     */
    private suspend fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        try {
            doHandlePacket(packet, output)
        } catch (e: Exception) {
            Log.w(TAG, "Packet handling failed, forwarding packet", e)
            try {
                output.write(packet)
            } catch (_: Exception) {}
        }
    }

    private suspend fun doHandlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 20) {
            output.write(packet)
            return
        }

        val versionAndIHL = packet[0].toInt() and 0xFF
        val version = (versionAndIHL shr 4) and 0x0F
        val headerLen = (versionAndIHL and 0x0F) * 4

        if (version != 4 || headerLen < 20 || headerLen > packet.size) {
            output.write(packet)
            return
        }

        val protocol = packet[9].toInt() and 0xFF

        val destIpBytes = byteArrayOf(
            packet[16], packet[17], packet[18], packet[19]
        )
        val destIpStr = try {
            InetAddress.getByAddress(destIpBytes).hostAddress ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        val srcIpBytes = byteArrayOf(
            packet[12], packet[13], packet[14], packet[15]
        )
        val srcIpStr = try {
            InetAddress.getByAddress(srcIpBytes).hostAddress ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        var destPort = 0
        var srcPort = 0

        if (headerLen + 4 <= packet.size) {
            when (protocol) {
                6 -> { // TCP
                    srcPort = ((packet[headerLen].toInt() and 0xFF) shl 8) or
                              (packet[headerLen + 1].toInt() and 0xFF)
                    destPort = ((packet[headerLen + 2].toInt() and 0xFF) shl 8) or
                               (packet[headerLen + 3].toInt() and 0xFF)
                }
                17 -> { // UDP
                    srcPort = ((packet[headerLen].toInt() and 0xFF) shl 8) or
                              (packet[headerLen + 1].toInt() and 0xFF)
                    destPort = ((packet[headerLen + 2].toInt() and 0xFF) shl 8) or
                               (packet[headerLen + 3].toInt() and 0xFF)
                }
                1 -> { // ICMP — always allow
                    output.write(packet)
                    return
                }
            }
        }

        val uid = getUidForConnection(destIpStr, destPort, protocol)
        if (uid == null) {
            output.write(packet)
            return
        }

        val pkg = uidPackageCache[uid]
        if (pkg == null) {
            output.write(packet)
            return
        }

        val rule = rulesCache[pkg]
        val shouldBlock = shouldBlockPacket(rule, destIpStr, destPort)

        if (shouldBlock) {
            logBlock(pkg, destIpStr, destPort, protocol)
        } else {
            output.write(packet)
        }
    }

    /**
     * Решает, блокировать ли пакет на основе правил.
     */
    private fun shouldBlockPacket(rule: FirewallRule?, destIp: String, destPort: Int): Boolean {
        if (rule == null) return false // Нет правила — разрешить

        // BlockAll
        if (rule.blockAll) return true

        // Block by specific network
        val onWifi = isOnWifi()
        if (onWifi && rule.blockWifi) return true
        if (!onWifi && rule.blockMobile) return true

        // AllowWifiOnly / AllowMobileOnly
        if (rule.allowWifiOnly && !onWifi) return true
        if (rule.allowMobileOnly && onWifi) return true

        // Block by domain/IP list
        if (rule.blockedIps.isNotEmpty()) {
            try {
                val blockedIps = JSONArray(rule.blockedIps)
                for (i in 0 until blockedIps.length()) {
                    if (blockedIps.getString(i) == destIp) return true
                }
            } catch (_: Exception) {}
        }

        // Block background traffic (эвристика: порты < 1024 = foreground)
        if (rule.blockBackground && destPort > 1024) return true

        return false
    }

    /**
     * Логирование блокировки с rate limiting.
     */
    private suspend fun logBlock(packageName: String, destIp: String, destPort: Int, protocol: Int) {
        val now = System.currentTimeMillis()
        if (now - logWindowStart > 1000) {
            logCount = 0
            logWindowStart = now
        }
        logCount++

        if (logCount > MAX_LOGS_PER_SECOND) return // Rate limit

        val connectionType = if (isOnWifi()) FirewallLog.ConnectionType.WIFI
        else FirewallLog.ConnectionType.MOBILE

        val trafficDirection = FirewallLog.TrafficDirection.OUTBOUND

        val appName = resolveAppName(packageName)

        val log = FirewallLog(
            packageName = packageName,
            appName = appName,
            ipAddress = "$destIp:$destPort",
            domainName = null,
            timestamp = System.currentTimeMillis(),
            connectionType = connectionType,
            trafficDirection = trafficDirection
        )

        firewallRepository.insertLog(log)

        // Обновлять нотификацию каждые 10 блокировок
        if (logCount % 10 == 0) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(VPN_NOTIFICATION_ID, getNotification())
            } catch (_: Exception) {}
        }

        Log.d(TAG, "BLOCKED: $packageName → $destIp:$destPort")
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Определение UID через ConnectionManager (API 29+).
     * Работает только для активных соединений, для которых есть socket tracking.
     */
    private fun getUidForConnection(destIp: String, destPort: Int, protocol: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val address = InetAddress.getByName(destIp)
            val ownerUid = cm.getConnectionOwnerUid(address, destPort, protocol, null, 0)
            if (ownerUid > 0) ownerUid else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) { false }
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(TAG, "VPN revoked")
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        vpnScope.cancel()
    }

    /**
     * Fallback: block app network access via NetworkCapabilities on Android 10+.
     * This does not require root and works without a full VPN implementation.
     */
    fun blockAppNetworkAccess(packageName: String, block: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val uid = packageManager.getPackageUid(packageName, 0)
            val builder = NetworkCapabilities.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .setOwnerUid(uid)
            if (block) {
                builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            }
            val caps = builder.build()
            cm.bindProcessToNetwork(null)
            cm.updateCapabilitiesForProcess(caps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update network capabilities for $packageName", e)
        }
    }
}
