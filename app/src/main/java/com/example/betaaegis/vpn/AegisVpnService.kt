package com.example.betaaegis.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.betaaegis.MainActivity
import com.example.betaaegis.vpn.policy.RuleEngine
import com.example.betaaegis.vpn.policy.UidResolver
import com.example.betaaegis.vpn.tcp.TcpForwarder
import com.example.betaaegis.vpn.udp.UdpForwarder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 1: Full-Capture VPN Service
 * Phase 2: TCP Stream Forwarding Added
 * Phase 3: Policy and UDP Forwarding Added
 *
 * PURPOSE:
 * - Capture ALL app traffic into the VPN
 * - Forward TCP connections via socket-based streams (Phase 2)
 * - Forward UDP flows via datagram sockets (Phase 3)
 * - Apply policy decisions per flow (Phase 3)
 * - Attribute flows to UIDs (Phase 3)
 *
 * PHASE 3 ADDITIONS:
 * - UidResolver: Best-effort app attribution
 * - RuleEngine: Per-UID policy evaluation
 * - UdpForwarder: UDP flow management
 * - Policy integration into TCP/UDP forwarders
 *
 * NON-GOALS (Still deferred):
 * - TLS inspection
 * - Domain-based rules
 * - Dynamic mid-flow enforcement
 */
class AegisVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReaderThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var telemetry: VpnTelemetry? = null
    private var tcpForwarder: TcpForwarder? = null // Phase 2
    private var udpForwarder: UdpForwarder? = null // Phase 3
    private var uidResolver: UidResolver? = null   // Phase 3
    private var ruleEngine: RuleEngine? = null     // Phase 3

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
                .setSession("Aegis VPN Phase 3")

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
                .setMtu(1500)

                // CRITICAL: Allow this app to bypass VPN to prevent routing loop
                // The VPN app must be able to make network calls without going through itself
                .setBlocking(true)

            // Apply self-bypass
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                // If we can't bypass ourselves, abort
                // This prevents catastrophic routing loops
                android.util.Log.e("AegisVPN", "Failed to bypass self: ${e.message}")
                return
            }

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
     *
     * CONSTRAINTS:
     * - The read loop is BLOCKING
     * - TCP packets are forwarded via TcpForwarder (Phase 2)
     * - UDP packets are forwarded via UdpForwarder (Phase 3)
     * - Policy is evaluated once per flow (Phase 3)
     */
    private fun startTunReader() {
        // Initialize streams
        val tunFileDescriptor = vpnInterface!!.fileDescriptor
        val tunInputStream = FileInputStream(tunFileDescriptor)
        val tunOutputStream = FileOutputStream(tunFileDescriptor)

        // Phase 3: Initialize policy components
        uidResolver = UidResolver()
        ruleEngine = RuleEngine(uidResolver!!)

        // Phase 2: Initialize TCP forwarder with policy (Phase 3)
        tcpForwarder = TcpForwarder(this, tunOutputStream, ruleEngine)

        // Phase 3: Initialize UDP forwarder with policy
        udpForwarder = UdpForwarder(this, tunOutputStream, ruleEngine!!)

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
            // Phase 2: Close all TCP flows first
            tcpForwarder?.closeAllFlows()
            tcpForwarder = null

            // Phase 3: Close all UDP flows
            udpForwarder?.closeAll()
            udpForwarder = null

            // Phase 3: Clear policy caches
            uidResolver?.clearCache()
            uidResolver = null
            ruleEngine = null

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
        .setContentText("Phase 3: TCP/UDP + Policy")
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
}

