package com.example.betaaegis.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.betaaegis.MainActivity
import com.example.betaaegis.telemetry.*
import com.example.betaaegis.vpn.dns.DomainCache
import com.example.betaaegis.vpn.policy.RuleEngine
import com.example.betaaegis.vpn.policy.UidResolver
import com.example.betaaegis.vpn.tcp.TcpForwarder
import com.example.betaaegis.vpn.udp.UdpForwarder
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 1: Full-Capture VPN Service
 * Phase 2: TCP Stream Forwarding Added
 * Phase 3: Policy and UDP Forwarding Added
 * Phase 4: DNS Inspection and Domain-Based Policy Added
 * Phase 5: Observability and Diagnostics Added
 *
 * PURPOSE:
 * - Capture ALL app traffic into the VPN
 * - Forward TCP connections via socket-based streams (Phase 2)
 * - Forward UDP flows via datagram sockets (Phase 3)
 * - Apply policy decisions per flow (Phase 3)
 * - Attribute flows to UIDs (Phase 3)
 * - Inspect DNS and cache domain mappings (Phase 4)
 * - Apply domain-based policy rules (Phase 4)
 * - Expose observability data for UI/diagnostics (Phase 5)
 *
 * PHASE 5 ADDITIONS:
 * - Statistics aggregation (read-only)
 * - Safe UI data exposure
 * - Zero data-plane impact
 *
 * NON-GOALS (Still deferred):
 * - TLS inspection
 * - DoT/DoH interception
 * - SNI inspection
 * - Dynamic mid-flow enforcement
 */
class AegisVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReaderThread: Thread? = null
    private val isRunning = AtomicBoolean(false)


    private var telemetry: VpnTelemetry? = null
    private var tcpForwarder: TcpForwarder? = null
    private var udpForwarder: UdpForwarder? = null
    private var uidResolver: UidResolver? = null
    private var ruleEngine: RuleEngine? = null
    private var domainCache: DomainCache? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "aegis_vpn_channel"
        const val ACTION_START_VPN = "com.example.betaaegis.START_VPN"
        const val ACTION_STOP_VPN = "com.example.betaaegis.STOP_VPN"
    }

    override fun onCreate() {
        super.onCreate()
        telemetry = VpnTelemetry()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VPN -> startVpn()
            ACTION_STOP_VPN -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    /**
     * 1️⃣ VPN CONFIGURATION (CRITICAL)
     *
     * This configuration ensures:
     * - ALL apps route into the VPN (via addAddress + addRoute)
     * - NO per-app routing rules exist (no addAllowedApplication)
     * - The VPN app itself bypasses to prevent loops (via package name)
     *
     * WHY addDisallowedApplication() MUST NOT BE USED:
     * - We want full capture of all traffic
     * - Selective exclusion would create blind spots
     * - Phase 1 is about visibility, not filtering
     *
     * WHAT TRAFFIC IS CAPTURED:
     * - All IPv4 traffic from all apps (0.0.0.0/0)
     * - All IPv6 traffic from all apps (::/0)
     *
     * WHY THIS IS FUTURE-SAFE:
     * - No enforcement logic to migrate later
     * - Clean separation: capture now, forward later
     * - No hidden kernel assumptions
     */
    private fun startVpn() {
        if (isRunning.get()) {
            return
        }

        try {

            val builder = Builder()
                // Set VPN session name
                .setSession("Aegis VPN Phase 4")

                // Configure TUN interface addresses
                // This creates a virtual network interface
                .addAddress("10.0.0.2", 24)
                .addAddress("fd00:1:2::1", 64)

                // Route ALL IPv4 traffic into VPN
                .addRoute("0.0.0.0", 0)

                // Route ALL IPv6 traffic into VPN
                .addRoute("::", 0)

                // Set DNS servers (required for establishment, not used in Phase 1)
                .addDnsServer("8.8.8.8")
                .addDnsServer("2001:4860:4860::8888")

                // Set MTU (standard Ethernet MTU)
                .setMtu(1400)

                .setBlocking(true)


            // 2️⃣ TUN INTERFACE ESTABLISHMENT
            // Establish the TUN interface and obtain file descriptor
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                android.util.Log.e("AegisVPN", "Failed to establish VPN interface")
                return
            }

            isRunning.set(true)


            // Start foreground service with notification
            startForeground(NOTIFICATION_ID, createNotification())

            // 3️⃣ Start background thread to read from TUN
            startTunReader()

            android.util.Log.i("AegisVPN", "VPN started successfully")

        } catch (e: Exception) {
            android.util.Log.e("AegisVPN", "Error starting VPN: ${e.message}", e)
            stopVpn()
        }
    }


    /**
     * 2️⃣ TUN INTERFACE READING
     *
     * Starts a background thread that reads from the TUN interface.
     *
     * Phase 1: Read and observe packets
     * Phase 2: Initialize TCP forwarder for stream forwarding
     * Phase 3: Initialize policy components and UDP forwarder
     * Phase 4: Initialize DNS inspection and domain cache
     *
     * CONSTRAINTS:
     * - The read loop is BLOCKING
     * - TCP packets are forwarded via TcpForwarder (Phase 2)
     * - UDP packets are forwarded via UdpForwarder (Phase 3)
     * - Policy is evaluated once per flow (Phase 3)
     * - DNS is inspected for domain attribution (Phase 4)
     */
    private fun startTunReader() {
        // Initialize streams
        val tunFileDescriptor = vpnInterface!!.fileDescriptor
        val tunOutputStream = FileOutputStream(tunFileDescriptor)

        // Phase 3: Initialize policy components
        uidResolver = UidResolver()
        ruleEngine = RuleEngine(uidResolver!!)

        // Phase 4: Initialize domain cache
        domainCache = DomainCache()

        // Phase 2: Initialize TCP forwarder with policy (Phase 3) and domain cache (Phase 4)
        tcpForwarder = TcpForwarder(this, tunOutputStream, ruleEngine, domainCache)

        // Phase 3: Initialize UDP forwarder with policy and domain cache (Phase 4)
        udpForwarder = UdpForwarder(this, tunOutputStream, ruleEngine!!, domainCache!!)

        tunReaderThread = Thread {
            val tunReader = TunReader(
                vpnInterface!!,
                telemetry!!,
                isRunning,
                tcpForwarder, // Phase 2
                udpForwarder  // Phase 3
            )
            tunReader.run()
        }.apply {
            name = "TunReaderThread"
            start()
        }
    }

    private fun stopVpn() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        try {

            tcpForwarder?.closeAllFlows()
            tcpForwarder = null

            // Phase 3: Close all UDP flows
            udpForwarder?.closeAll()
            udpForwarder = null

            // Phase 3: Clear policy caches
            uidResolver?.clearCache()
            uidResolver = null
            ruleEngine = null

            // Phase 4: Clear domain cache
            domainCache?.clear()
            domainCache = null

            // Stop reader thread
            tunReaderThread?.interrupt()
            tunReaderThread?.join(1000)
            tunReaderThread = null

            // Close VPN interface
            vpnInterface?.close()
            vpnInterface = null


            // Log final telemetry
            telemetry?.let {
                android.util.Log.i("AegisVPN", "Final telemetry: ${it.getSnapshot()}")
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            android.util.Log.i("AegisVPN", "VPN stopped successfully")

        } catch (e: Exception) {
            android.util.Log.e("AegisVPN", "Error stopping VPN: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aegis VPN Service Status"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Aegis VPN Active")
        .setContentText("Phase 5: Observability & Diagnostics")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    fun createAndConnectProtectedTcpSocket(
        destIp: InetAddress,
        destPort: Int,
        timeoutMs: Int = 10_000
    ): Socket {
        if (!isRunning.get() || vpnInterface == null) {
            throw IOException("VPN service not running")
        }

        val socket = Socket()

        val ok = protect(socket)
        if (!ok) {
            socket.close()
            throw IOException("VpnService.protect() failed for TCP socket")
        }

        socket.connect(InetSocketAddress(destIp, destPort), timeoutMs)
        return socket
    }

    /**
     * Phase 5: Collect VPN statistics for observability.
     *
     * SAFETY GUARANTEES:
     * - Read-only operation
     * - Never blocks forwarding
     * - Safe to call from UI thread
     * - Returns empty stats if VPN not running
     * - Never throws exceptions
     *
     * @return VpnStatistics snapshot
     */
    fun getStatistics(): VpnStatistics {
        if (!isRunning.get()) {
            return VpnStatistics()
        }

        val collector = VpnStatisticsCollector()

        return collector.collectStatistics(
            getVpnTelemetry = {
                telemetry?.getSnapshot()?.let {
                    VpnTelemetrySnapshot(
                        packetCount = it.packetCount,
                        byteCount = it.byteCount,
                        lastPacketTimestamp = it.lastPacketTimestamp
                    )
                } ?: VpnTelemetrySnapshot(0, 0, 0)
            },
            getTcpStats = {
                tcpForwarder?.getStatsSnapshot() ?: TcpStatsSnapshot(0, 0, 0, 0, 0)
            },
            getUdpStats = {
                udpForwarder?.getStatsSnapshot() ?: UdpStatsSnapshot(0, 0, 0, 0, 0, 0)
            },
            getDnsStats = {
                domainCache?.getStatsSnapshot() ?: DnsStatsSnapshot(0, 0, 0)
            }
        )
    }

    /**
     * Phase 5: Check if VPN is currently running.
     */
    fun isVpnRunning(): Boolean {
        return isRunning.get()
    }
}
