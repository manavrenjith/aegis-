package com.example.betaaegis.vpn.udp

import android.net.VpnService
import android.util.Log
import com.example.betaaegis.vpn.policy.FlowDecision
import com.example.betaaegis.vpn.policy.RuleEngine
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 3: UDP Flow Forwarder
 *
 * Manages UDP flow lifecycle and forwarding.
 *
 * UDP PSEUDO-FLOW MODEL:
 * - Connectionless protocol treated as flows
 * - 5-tuple key identifies flow
 * - Idle timeout for cleanup
 * - Policy evaluated once per flow
 *
 * POLICY INTEGRATION:
 * - Decision made on first packet
 * - ALLOW: Create socket, forward
 * - BLOCK: Drop silently, no socket
 *
 * ENFORCEMENT:
 * - Blocked flows never create UdpConnection
 * - App times out naturally
 * - No packet injection
 */
class UdpForwarder(
    private val vpnService: VpnService,
    private val tunOutputStream: FileOutputStream,
    private val ruleEngine: RuleEngine
) {
    private val flows = ConcurrentHashMap<UdpFlowKey, UdpConnection>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            name = "UdpForwarder-Worker"
            isDaemon = true
        }
    }
    private val cleanupExecutor = Executors.newScheduledThreadPool(1) { runnable ->
        Thread(runnable).apply {
            name = "UdpForwarder-Cleanup"
            isDaemon = true
        }
    }
    private val stats = UdpStats()

    companion object {
        private const val TAG = "UdpForwarder"
        private const val IDLE_TIMEOUT_MS = 60_000L // 1 minute
        private const val CLEANUP_INTERVAL_SECONDS = 30L
    }

    init {
        // Start periodic cleanup of idle flows
        cleanupExecutor.scheduleAtFixedRate(
            { cleanupIdleFlows() },
            CLEANUP_INTERVAL_SECONDS,
            CLEANUP_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /**
     * Thread-safe statistics for telemetry.
     */
    class UdpStats {
        val bytesUplink = AtomicLong(0)
        val bytesDownlink = AtomicLong(0)
        val activeFlowCount = AtomicInteger(0)
        val totalFlowsCreated = AtomicLong(0)
        val totalFlowsClosed = AtomicLong(0)
        val totalFlowsBlocked = AtomicLong(0)

        override fun toString(): String {
            return "UDP Stats: flows=${activeFlowCount.get()}, " +
                   "up=${bytesUplink.get()}, down=${bytesDownlink.get()}, " +
                   "created=${totalFlowsCreated.get()}, closed=${totalFlowsClosed.get()}, " +
                   "blocked=${totalFlowsBlocked.get()}"
        }
    }

    /**
     * Handle UDP packet from TUN interface.
     *
     * This is the ONLY path for UDP packets in Phase 3.
     *
     * @param packet Raw IP/UDP packet from TUN
     */
    fun handleUdpPacket(packet: ByteArray) {
        try {
            val metadata = UdpPacketParser.parse(packet)

            val flowKey = UdpFlowKey(
                metadata.srcIp,
                metadata.srcPort,
                metadata.destIp,
                metadata.destPort
            )

            // Get or create flow
            var connection = flows[flowKey]
            if (connection == null) {
                connection = createFlow(flowKey, metadata)
                if (connection != null) {
                    flows[flowKey] = connection
                }
            }

            // Forward payload if flow is active
            if (connection != null && metadata.payload.isNotEmpty()) {
                connection.sendToServer(metadata.payload)
                stats.bytesUplink.addAndGet(metadata.payload.size.toLong())
            }

        } catch (e: IllegalArgumentException) {
            // Malformed packet - log and drop
            Log.w(TAG, "Malformed UDP packet: ${e.message}")
        } catch (e: Exception) {
            // Unexpected error - log but don't crash
            Log.e(TAG, "Error handling UDP packet", e)
        }
    }

    /**
     * Create a new UDP flow.
     *
     * POLICY EVALUATION:
     * - Evaluate rule once on flow creation
     * - Decision is cached in flow object
     * - BLOCK: Return null, no socket created
     * - ALLOW: Create connection and socket
     */
    private fun createFlow(key: UdpFlowKey, metadata: UdpMetadata): UdpConnection? {
        // Evaluate policy (ONCE per flow)
        val decision = ruleEngine.evaluate(
            protocol = "udp",
            srcIp = metadata.srcIp,
            srcPort = metadata.srcPort,
            destIp = metadata.destIp,
            destPort = metadata.destPort
        )

        if (decision == FlowDecision.BLOCK) {
            Log.d(TAG, "UDP flow blocked: $key")
            stats.totalFlowsBlocked.incrementAndGet()
            // Return null - no connection object created
            // App will timeout naturally
            return null
        }

        // ALLOW: Create connection
        Log.d(TAG, "New UDP flow: $key")

        val connection = UdpConnection(
            key = key,
            vpnService = vpnService,
            tunOutputStream = tunOutputStream,
            decision = decision
        )

        stats.activeFlowCount.incrementAndGet()
        stats.totalFlowsCreated.incrementAndGet()

        // Initialize asynchronously
        executor.execute {
            try {
                connection.initialize()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UDP flow: $key", e)
                closeFlow(key)
            }
        }

        return connection
    }

    /**
     * Close a specific flow.
     */
    private fun closeFlow(key: UdpFlowKey) {
        val connection = flows.remove(key)
        if (connection != null) {
            connection.close()
            stats.activeFlowCount.decrementAndGet()
            stats.totalFlowsClosed.incrementAndGet()
        }
    }

    /**
     * Cleanup idle flows (periodic task).
     */
    private fun cleanupIdleFlows() {
        try {
            val idleKeys = flows.keys.filter { key ->
                val connection = flows[key]
                connection != null && connection.isIdle(IDLE_TIMEOUT_MS)
            }

            idleKeys.forEach { key ->
                Log.d(TAG, "Closing idle UDP flow: $key")
                closeFlow(key)
            }

            if (idleKeys.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${idleKeys.size} idle UDP flows")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during UDP flow cleanup", e)
        }
    }

    /**
     * Close all UDP flows.
     */
    fun closeAll() {
        Log.i(TAG, "Closing all UDP flows")

        flows.keys.forEach { key ->
            closeFlow(key)
        }

        cleanupExecutor.shutdown()
        executor.shutdown()

        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }

    /**
     * Get statistics (for telemetry).
     */
    fun getStats(): String {
        return stats.toString()
    }
}

