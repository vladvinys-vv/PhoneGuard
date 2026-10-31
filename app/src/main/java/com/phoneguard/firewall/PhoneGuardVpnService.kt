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
import com.phoneguard.intrusion.IntrusionRepository
import com.phoneguard.intrusion.IntrusionEvent
import com.phoneguard.intrusion.IntrusionSeverity
import com.phoneguard.intrusion.IntrusionType
import com.phoneguard.model.FirewallLog
import com.phoneguard.firewall.packet.*
import com.phoneguard.model.FirewallRule
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
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

    @Inject
    lateinit var rulesManager: FirewallRulesManager

    @Inject
    lateinit var intrusionRepository: IntrusionRepository

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val vpnScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Pre-allocated buffers to reduce GC pressure
    private val ipHeaderBuffer = ByteArray(60)
    private val packetBuffer = ByteArray(65535)

    /** Rate limiter для логов */
    private var logCount = 0
    private var logWindowStart = 0L

    private val portScanTracker = mutableMapOf<Int, MutableSet<Int>>()
    private val portScanWindowStart = mutableMapOf<Int, Long>()
    private var malformedPacketCount = 0
    private var malformedWindowStart = 0L

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
            .setContentTitle(getString(R.string.firewall_active))
            .setContentText(getString(R.string.firewall_blocking_traffic, blockedCount))
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
                rulesManager.loadRules()
                rulesManager.loadInstalledApps(packageManager)
                Log.d(TAG, "Loaded ${rulesManager.getRulesSnapshot().size} rules, ${rulesManager.getPackageNameForUid(0)?.let { "installed apps" } ?: "installed apps"}")

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
        val ipv4 = IPv4PacketParser.parse(packet)
        if (ipv4 == null) {
            trackMalformedPackets()
            output.write(packet)
            return
        }

        val protocol = ipv4.protocol
        val destIpStr = ipv4.destinationAddress.hostAddress ?: "unknown"
        val srcIpStr = ipv4.sourceAddress.hostAddress ?: "unknown"
        val headerLen = ipv4.headerLength

        var destPort = 0
        var srcPort = 0

        if (protocol == 1) { // ICMP — always allow
            output.write(packet)
            return
        }

        if (ipv4.canParseTransportHeader) {
            when (protocol) {
                6 -> TcpPacketParser.parse(packet, ipv4)?.let {
                    srcPort = it.sourcePort
                    destPort = it.destinationPort
                }
                17 -> UdpPacketParser.parse(packet, ipv4)?.let {
                    srcPort = it.sourcePort
                    destPort = it.destinationPort
                }
            }
        }

        val uid = getUidForConnection(srcPort, destIpStr, destPort, protocol)
        if (uid == null) {
            output.write(packet)
            return
        }

        if (uid != null) trackPortScan(uid, destPort)
        val pkg = rulesManager.getPackageNameForUid(uid)
        if (pkg == null) {
            output.write(packet)
            return
        }

        val rule = rulesManager.getRuleForPackage(pkg)
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
    private fun getUidForConnection(
        srcPort: Int,
        destIp: String,
        destPort: Int,
        protocol: Int
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val local = InetSocketAddress(srcPort)
            val remote = InetSocketAddress(InetAddress.getByName(destIp), destPort)
            val ownerUid = cm.getConnectionOwnerUid(protocol, local, remote)
            if (ownerUid > 0) ownerUid else null
        } catch (e: Exception) {
            null
        }
    }

    private fun trackMalformedPackets() {
        val now = System.currentTimeMillis()
        if (now - malformedWindowStart > 10000) {
            malformedPacketCount = 0
            malformedWindowStart = now
        }
        malformedPacketCount++
        if (malformedPacketCount > 100) {
            vpnScope.launch {
                intrusionRepository.recordEvent(
                    IntrusionEvent(
                        type = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
                        severity = IntrusionSeverity.MEDIUM,
                        evidence = mapOf("reason" to "malformed_packet_flood", "count" to malformedPacketCount.toString())
                    )
                )
            }
            malformedPacketCount = 0
        }
    }

    private fun trackPortScan(uid: Int, destPort: Int) {
        val now = System.currentTimeMillis()
        val windowStart = portScanWindowStart[uid] ?: now
        if (now - windowStart > 10000) {
            portScanTracker[uid]?.clear()
            portScanWindowStart[uid] = now
        } else if (portScanWindowStart[uid] == null) {
            portScanWindowStart[uid] = now
        }
        val ports = portScanTracker.getOrPut(uid) { mutableSetOf() }
        ports += destPort
        if (ports.size > 30) {
            vpnScope.launch {
                intrusionRepository.recordEvent(
                    IntrusionEvent(
                        type = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
                        severity = IntrusionSeverity.HIGH,
                        uid = uid,
                        packageName = rulesManager.getPackageNameForUid(uid),
                        evidence = mapOf("reason" to "port_scan", "distinct_ports" to ports.size.toString())
                    )
                )
            }
            ports.clear()
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

}
