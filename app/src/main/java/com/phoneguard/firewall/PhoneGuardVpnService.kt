package com.phoneguard.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phoneguard.R
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.firewall.packet.IPv4PacketParser
import com.phoneguard.firewall.packet.TcpPacketParser
import com.phoneguard.firewall.packet.UdpPacketParser
import com.phoneguard.intrusion.IntrusionEvent
import com.phoneguard.intrusion.IntrusionRepository
import com.phoneguard.intrusion.IntrusionSeverity
import com.phoneguard.intrusion.IntrusionType
import com.phoneguard.model.FirewallLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject

/**
 * VPN-based firewall без root.
 *
 * Как это работает (и чем отличается от прежней версии):
 *
 *  - Раньше сервис забирал **весь** трафик устройства в TUN-интерфейс и просто
 *    «писал пакеты обратно» в тот же дескриптор. Запись в TUN — это входящий
 *    пакет для приложения, а не выход в интернет, поэтому включение фаервола
 *    обрывало *всю* сеть и при этом ничего не блокировало.
 *  - Теперь в туннель попадают только приложения, чьё правило блокирует весь
 *    трафик прямо сейчас (`blockAll`, `blockWifi`/`blockMobile` для текущей
 *    сети, `allowWifiOnly`/`allowMobileOnly`). Остальные приложения
 *    направляются мимо VPN и работают как обычно.
 *  - Пакеты из туннеля сознательно **отбрасываются**: это и есть блокировка.
 *    Форвардинг в userspace невозможен без отдельного upstream-стека, поэтому
 *    вместо иллюзии форвардинга реализовано честное «дропнуть всё».
 *  - При смене типа сети (Wi-Fi ↔ мобильная) набор заблокированных приложений
 *    пересчитывается, и туннель пересоздаётся.
 */
@AndroidEntryPoint
class PhoneGuardVpnService : VpnService() {

    companion object {
        private const val TAG = "PGVpn"
        private const val VPN_NOTIFICATION_ID = 1001
        private const val VPN_CHANNEL_ID = "phoneguard_vpn_channel"
        const val ACTION_CONNECT = "com.phoneguard.CONNECT"
        const val ACTION_DISCONNECT = "com.phoneguard.DISCONNECT"

        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_MTU = 1500
        private const val TUN_NETWORK = "0.0.0.0"
        private const val TUN_PREFIX = 0

        // Без IPv6-маршрута заблокированное приложение продолжало бы ходить в сеть
        // по IPv6 (мобильные операторы всё чаще дают IPv6-only + 464XLAT):
        // туннель забирает только IPv4. Пакеты IPv6 попадают в туннель и
        // отбрасываются так же, как IPv4.
        private const val TUN_ADDRESS_V6 = "fd00:2:fd00:1:fd00:1:fd00:1"
        private const val TUN_NETWORK_V6 = "::"
        private const val TUN_PREFIX_V6 = 0
        private const val IP_VERSION_IPV6 = 6

        /**
         * Как часто обновлять текст уведомления о блокировках (в штуках пакетов).
         * Пороги ограничителей частоты живут в [PacketRateLimiter].
         */
        private const val NOTIFICATION_UPDATE_EVERY = 10

        /** Системные приложения не «сканируют порты» — они делают свою работу. */
        private val SYSTEM_PACKAGE_PREFIXES = listOf("android", "com.android.", "com.google.android.")
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

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastWifiState: Boolean? = null

    /**
     * Счётчик заблокированных пакетов (для уведомления).
     *
     * `@Volatile`: увеличивается в цикле чтения туннеля, а читается из
     * `buildNotification()` на другом потоке — без этого уведомление могло
     * показывать устаревшее значение.
     */
    @Volatile
    private var blockedCount = 0

    /** Значение [blockedCount] на момент последнего обновления уведомления. */
    @Volatile
    private var notifiedBlockedCount = 0

    /**
     * Окна ограничения частоты записей и детекторов (см. [PacketRateLimiter]).
     *
     * Вынесены из сервиса: раньше счётчики считались по системному времени и
     * менялись из разных потоков без синхронизации.
     */
    private val rateLimiter = PacketRateLimiter()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CONNECT -> {
                // Сервис запускается через startForegroundService — уведомление
                // обязательно показать в первые 5 секунд.
                startForegroundSafe()
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

    private fun startForegroundSafe() {
        try {
            // На Android 14+ тип foreground-сервиса обязателен и должен
            // совпадать с android:foregroundServiceType в манифесте.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    VPN_NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(VPN_NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_CHANNEL_ID,
                getString(R.string.firewall_active),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.firewall_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // getLaunchIntentForPackage может вернуть null (нет launcher-activity у пакета),
        // а PendingIntent.getActivity(null) падает NPE — тогда уведомление без клика.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, VPN_CHANNEL_ID)
            .setContentTitle(getString(R.string.firewall_active))
            .setContentText(getString(R.string.firewall_blocking_traffic, blockedCount))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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

                val onWifi = isOnWifi()
                lastWifiState = onWifi
                val blockedPackages = rulesManager.blockedPackages(onWifi)

                if (blockedPackages.isEmpty()) {
                    // Нет приложений, которым сейчас нужна блокировка: не создаём
                    // туннель, иначе сеть устройства просто перестала бы работать.
                    Log.d(TAG, "No apps to block (wifi=$onWifi) — VPN not needed")
                    stopSelfSafely()
                    return@launch
                }

                val builder = Builder()
                    .addAddress(TUN_ADDRESS, 32)
                    .addRoute(TUN_NETWORK, TUN_PREFIX)
                    .addAddress(TUN_ADDRESS_V6, 128)
                    .addRoute(TUN_NETWORK_V6, TUN_PREFIX_V6)
                    .setMtu(TUN_MTU)
                    .setSession("PhoneGuard Firewall")

                var added = 0
                blockedPackages.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                        added++
                    } catch (_: PackageManager.NameNotFoundException) {
                        // Приложение удалено — правило осталось, просто пропускаем.
                    }
                }

                if (added == 0) {
                    Log.d(TAG, "None of the blocked packages is installed — VPN not needed")
                    stopSelfSafely()
                    return@launch
                }

                val iface = builder.establish()
                if (iface == null) {
                    Log.e(TAG, "Failed to establish VPN interface")
                    stopSelfSafely()
                    return@launch
                }

                vpnInterface = iface
                Log.d(TAG, "VPN started for $added app(s), wifi=$onWifi")
                startForegroundSafe()
                registerNetworkCallback()
                runVpnLoop(iface)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                stopSelfSafely()
            }
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // ignore
        }
        stopSelf()
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        rateLimiter.reset()
        unregisterNetworkCallback()
        vpnJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // ignore
        }
        stopSelf()
    }

    /**
     * Цикл чтения туннеля.
     *
     * `suspend`, потому что внутри вызывается `suspend fun handlePacket(...)`:
     * раньше `handlePacket` был suspend, а цикл — обычной функцией, и это
     * не компилировалось («suspension functions can be called only within a
     * coroutine body»). Вызывается из `vpnScope.launch`, блокирующий `read`
     * висит на IO-диспетчере и разблокируется закрытием дескриптора.
     */
    private suspend fun runVpnLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val buffer = ByteArray(TUN_MTU)

        try {
            while (vpnScope.isActive && vpnInterface === iface) {
                val bytesRead = try {
                    input.read(buffer)
                } catch (e: Exception) {
                    if (vpnScope.isActive) Log.e(TAG, "Error reading VPN input", e)
                    break
                }
                if (bytesRead <= 0) continue
                val packet = buffer.copyOf(bytesRead)
                try {
                    handlePacket(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Packet handling failed", e)
                }
            }
        } finally {
            runCatching { input.close() }
            Log.d(TAG, "VPN loop ended")
            // Цикл закончился сам (EOF/ошибка чтения), а сервис остался бы
            // foreground-сервисом с «активным» уведомлением: заблокированные
            // приложения уже ходят в сеть мимо туннеля, а повторный CONNECT
            // упирался бы в проверку `vpnInterface != null` и ничего не делал.
            // Поэтому состояние сбрасываем и останавливаемся.
            if (vpnInterface === iface) {
                vpnInterface = null
                if (vpnScope.isActive) {
                    vpnScope.launch { stopVpn() }
                } else {
                    stopVpn()
                }
            }
        }
    }

    /**
     * Разбирает пакет, определяет приложение и записывает блокировку.
     * Пакет не форвардится — он отбрасывается (это и есть блокировка).
     */
    private suspend fun handlePacket(packet: ByteArray) {
        // IPv6-пакеты тоже приходят в туннель (см. TUN_NETWORK_V6) и просто
        // отбрасываются: разбирать их ради журнала не нужно, но и «битыми» они не являются.
        if (packet.isNotEmpty() && (packet[0].toInt() ushr 4) == IP_VERSION_IPV6) {
            blockedCount++
            maybeUpdateNotification()
            return
        }
        val ipv4 = IPv4PacketParser.parse(packet)
        if (ipv4 == null) {
            trackMalformedPackets()
            return
        }

        val protocol = ipv4.protocol
        val destIpStr = ipv4.destinationAddress.hostAddress ?: return
        val srcIpStr = ipv4.sourceAddress.hostAddress ?: return

        var destPort = 0
        var srcPort = 0
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

        val uid = getUidForConnection(srcIpStr, srcPort, destIpStr, destPort, protocol)
        if (uid != null) {
            trackPortScan(uid, destPort)
        }

        val packageName = uid?.let { owner ->
            rulesManager.getPackageNamesForUid(owner).firstOrNull { pkg ->
                val rule = rulesManager.getRuleForPackage(pkg)
                rule != null && rulesManager.shouldBlockAllTraffic(rule, isOnWifi())
            }
        } ?: uid?.let { rulesManager.getPackageNameForUid(it) }

        logBlock(packageName ?: UNKNOWN_PACKAGE, destIpStr, destPort, protocol)
    }

    /** Логирование блокировки с ограничением частоты записей. */
    private suspend fun logBlock(packageName: String, destIp: String, destPort: Int, protocol: Int) {
        blockedCount++
        // Уведомление обновляется ниже — после проверки лимита записей,
        // повторный вызов здесь только дублировал notify().

        if (!rateLimiter.allowLog(SystemClock.elapsedRealtime())) return

        // В журнале — «человеческое» время; окна ограничителя считаются отдельно,
        // по монотонным часам.
        val now = System.currentTimeMillis()
        val connectionType = if (isOnWifi()) FirewallLog.ConnectionType.WIFI
        else FirewallLog.ConnectionType.MOBILE

        val log = FirewallLog(
            packageName = packageName,
            appName = resolveAppName(packageName),
            ipAddress = "$destIp:$destPort",
            domainName = null,
            timestamp = now,
            connectionType = connectionType,
            trafficDirection = FirewallLog.TrafficDirection.OUTBOUND
        )

        runCatching { firewallRepository.insertLog(log) }

        // Счётчик вырос — обновляем текст уведомления (раз в NOTIFICATION_UPDATE_EVERY).
        maybeUpdateNotification()

        Log.d(TAG, "BLOCKED protocol=$protocol pkg=$packageName → $destIp:$destPort")
    }

    /**
     * Обновляет постоянное уведомление каждые [NOTIFICATION_UPDATE_EVERY] блокировок.
     *
     * Раньше условие было `blockedCount % 10 == 0` и стояло после ограничителя
     * частоты логов: при всплеске трафика записи отбрасывались вместе с обновлением,
     * а деление «в лоб» срабатывало нестабильно (счётчик мог перескочить кратное).
     */
    private fun maybeUpdateNotification() {
        val blocked = blockedCount
        if (blocked - notifiedBlockedCount < NOTIFICATION_UPDATE_EVERY) return
        notifiedBlockedCount = blocked
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(VPN_NOTIFICATION_ID, buildNotification())
        }
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    /**
     * Определение UID владельца сокета (API 29+).
     *
     * Раньше локальный адрес создавался как `InetSocketAddress(port)` — без IP,
     * поэтому `getConnectionOwnerUid` почти всегда возвращал ошибку и пакеты
     * уходили «мимо» правил.
     */
    private fun getUidForConnection(
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        protocol: Int
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val local = InetSocketAddress(InetAddress.getByName(srcIp), srcPort)
            val remote = InetSocketAddress(InetAddress.getByName(destIp), destPort)
            val ownerUid = cm.getConnectionOwnerUid(protocol, local, remote)
            if (ownerUid > 0) ownerUid else null
        } catch (_: Exception) {
            null
        }
    }

    private fun trackMalformedPackets() {
        if (rateLimiter.registerMalformedPacket(SystemClock.elapsedRealtime())) {
            vpnScope.launch {
                runCatching {
                    intrusionRepository.recordEvent(
                        IntrusionEvent(
                            type = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
                            severity = IntrusionSeverity.MEDIUM,
                            evidence = mapOf("reason" to "malformed_packet_flood")
                        )
                    )
                }
            }
        }
    }

    /**
     * Грубая эвристика «приложение перебирает порты».
     *
     * Раньше событие `HIGH` создавалось уже на 30 разных портах за 10 секунд.
     * Это нормальный профиль для браузера, мессенджера или игры (QUIC, P2P,
     * рекламные сети), поэтому детектор срабатывал на штатное поведение и мог
     * включить автоматическую реакцию (сирена, фото, SMS). Теперь:
     *  - порог 60 портов за 10 секунд;
     *  - системные приложения и сам PhoneGuard не проверяются;
     *  - на один UID — не больше одного события в 10 минут.
     */
    private fun trackPortScan(uid: Int, destPort: Int) {
        val packageName = rulesManager.getPackageNameForUid(uid)
        if (packageName == null || isIgnoredForPortScan(packageName)) return

        if (rateLimiter.shouldReportPortScan(uid, destPort, SystemClock.elapsedRealtime())) {
            vpnScope.launch {
                runCatching {
                    intrusionRepository.recordEvent(
                        IntrusionEvent(
                            type = IntrusionType.ANALYSIS_OR_PARSER_ATTEMPT,
                            severity = IntrusionSeverity.HIGH,
                            uid = uid,
                            packageName = packageName,
                            evidence = mapOf("reason" to "port_scan")
                        )
                    )
                }
            }
        }
    }

    private fun isIgnoredForPortScan(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        return SYSTEM_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Пересоздаёт туннель при смене типа сети: набор заблокированных приложений
     * зависит от Wi-Fi/мобильной сети.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()

            override fun onLost(network: Network) = onNetworkChanged()
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            networkCallback = callback
        }.onFailure { Log.w(TAG, "Cannot register network callback", it) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(callback)
        }
    }

    private fun onNetworkChanged() {
        vpnScope.launch {
            val onWifi = isOnWifi()
            if (onWifi == lastWifiState) return@launch
            lastWifiState = onWifi
            Log.d(TAG, "Network type changed (wifi=$onWifi), rebuilding tunnel")
            rulesManager.loadRules()
            val blocked = rulesManager.blockedPackages(onWifi)
            if (blocked.isEmpty()) {
                stopVpn()
            } else {
                restartTunnel()
            }
        }
    }

    private fun restartTunnel() {
        vpnJob?.cancel()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        startVpn()
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

private const val UNKNOWN_PACKAGE = "unknown"
