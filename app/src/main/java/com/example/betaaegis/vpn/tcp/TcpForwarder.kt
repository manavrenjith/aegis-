package com.example.betaaegis.vpn.tcp

import android.net.VpnService
import android.util.Log
import com.example.betaaegis.vpn.policy.FlowDecision
import com.example.betaaegis.vpn.policy.RuleEngine
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 2: TCP Stream Forwarder
 * Phase 3: Policy Integration Added
 */
class TcpForwarder(
    private val vpnService: VpnService,
    private val tunOutputStream: FileOutputStream,
    private val ruleEngine: RuleEngine? = null // Phase 3: Optional policy engine
) {
    private val flows = ConcurrentHashMap<TcpFlowKey, TcpConnection>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            name = "TcpForwarder-Worker"
            isDaemon = true
        }
    }
    private val stats = TcpStats()

    companion object {
        private const val TAG = "TcpForwarder"
    }

    /**
     * Thread-safe statistics for telemetry.
     *
     * Rules:
     * - Read-only access for telemetry
     * - Never blocks forwarding logic
     * - Failures don't affect VPN stability
     */
    class TcpStats {
        val bytesUplink = AtomicLong(0)
        val bytesDownlink = AtomicLong(0)
        val activeFlowCount = AtomicInteger(0)
        val totalFlowsCreated = AtomicLong(0)
        val totalFlowsClosed = AtomicLong(0)

        override fun toString(): String {
            return "TCP Stats: flows=${activeFlowCount.get()}, " +
                   "up=${bytesUplink.get()}, down=${bytesDownlink.get()}, " +
                   "created=${totalFlowsCreated.get()}, closed=${totalFlowsClosed.get()}"
        }
    }

    /**
     * Handle TCP packet from TUN interface.
     *
     * This is the ONLY path for TCP packets in Phase 2.
     * No passive logic exists.
     *
     * @param packet Raw IP/TCP packet from TUN
     */
    fun handleTcpPacket(packet: ByteArray) {
        try {
            val metadata = TcpPacketParser.parse(packet)

            val flowKey = TcpFlowKey(
                metadata.srcIp,
                metadata.srcPort,
                metadata.destIp,
                metadata.destPort
            )

            when {
                metadata.isSyn && !metadata.isAck -> {
                    // New connection request from app
                    handleNewConnection(flowKey, metadata)
                }
                metadata.isRst -> {
                    // Reset from app
                    handleReset(flowKey)
                }
                metadata.isFin -> {
                    // Close request from app
                    handleFinFromApp(flowKey)
                }
                metadata.isAck && metadata.payload.isEmpty() -> {
                    // Pure ACK (no data)
                    handleAck(flowKey, metadata)
                }
                metadata.payload.isNotEmpty() -> {
                    // Data packet from app
                    handleDataFromApp(flowKey, metadata)
                }
                else -> {
                    // Other packet types (ignore in Phase 2)
                    Log.v(TAG, "Ignoring packet: $flowKey, flags=${metadata.isSyn}/${metadata.isAck}/${metadata.isFin}/${metadata.isRst}")
                }
            }

        } catch (e: IllegalArgumentException) {
            // Malformed packet - log and drop
            Log.w(TAG, "Malformed TCP packet: ${e.message}")
        } catch (e: Exception) {
            // Unexpected error - log but don't crash
            Log.e(TAG, "Error handling TCP packet", e)
        }
    }

    /**
     * Handle new TCP connection (SYN from app).
     *
     * Phase 3: Policy evaluation added
     */
    private fun handleNewConnection(key: TcpFlowKey, metadata: TcpMetadata) {
        // Check if flow already exists (duplicate SYN)
        if (flows.containsKey(key)) {
            Log.d(TAG, "Duplicate SYN for existing flow: $key")
            return
        }

        // Phase 3: Evaluate policy if engine is available
        if (ruleEngine != null) {
            val decision = ruleEngine.evaluate(
                protocol = "tcp",
                srcIp = key.srcIp,
                srcPort = key.srcPort,
                destIp = key.destIp,
                destPort = key.destPort
            )

            if (decision == FlowDecision.BLOCK) {
                Log.d(TAG, "TCP flow blocked by policy: $key")
                // Send RST to app (connection rejected)
                sendRstForKey(key)
                return
            }
        }

        Log.d(TAG, "New connection: $key")

        val connection = TcpConnection(
            key = key,
            vpnService = vpnService,
            tunOutputStream = tunOutputStream
        )

        flows[key] = connection
        stats.activeFlowCount.incrementAndGet()
        stats.totalFlowsCreated.incrementAndGet()

        // Asynchronously connect to destination
        executor.execute {
            try {
                // Connect to remote server (socket is protected inside)
                connection.connect()

                // Send SYN-ACK to app
                connection.sendSynAck()

                // Start bidirectional forwarding
                connection.startForwarding(stats.bytesDownlink)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed for $key: ${e.message}", e)

                // Send RST to app (connection rejected)
                connection.sendRst()

                // Clean up
                closeFlow(key)
            }
        }
    }

    /**
     * Handle data from app (uplink).
     *
     * Extract payload and write to socket stream.
     */
    private fun handleDataFromApp(key: TcpFlowKey, metadata: TcpMetadata) {
        val connection = flows[key]

        if (connection == null) {
            // No connection exists - send RST
            Log.w(TAG, "Data for unknown flow: $key")
            sendRstForKey(key)
            return
        }

        if (metadata.payload.isNotEmpty()) {
            connection.sendToServer(metadata.payload)
            stats.bytesUplink.addAndGet(metadata.payload.size.toLong())
        }

        // Update ACK tracking
        connection.handleAck(metadata.ackNum)
    }

    /**
     * Handle pure ACK from app.
     */
    private fun handleAck(key: TcpFlowKey, metadata: TcpMetadata) {
        val connection = flows[key] ?: return
        connection.handleAck(metadata.ackNum)
    }

    /**
     * Handle FIN from app (graceful close request).
     *
     * State transition: ESTABLISHED -> CLOSING -> CLOSED
     */
    private fun handleFinFromApp(key: TcpFlowKey) {
        Log.d(TAG, "FIN from app: $key")

        val connection = flows[key]
        if (connection != null) {
            connection.closeGracefully()
            closeFlow(key)
        }
    }

    /**
     * Handle RST from app (immediate close).
     *
     * State transition: Any -> CLOSED
     */
    private fun handleReset(key: TcpFlowKey) {
        Log.d(TAG, "RST from app: $key")
        closeFlow(key)
    }

    /**
     * Send RST for a flow key (when no connection exists).
     */
    private fun sendRstForKey(key: TcpFlowKey) {
        try {
            val packet = TcpPacketBuilder.build(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                flags = 0x04, // RST
                seqNum = 0,
                ackNum = 0,
                payload = byteArrayOf()
            )

            synchronized(tunOutputStream) {
                tunOutputStream.write(packet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send RST for $key", e)
        }
    }

    /**
     * Close and remove a flow.
     *
     * Cleanup order:
     * 1. Remove from flow map
     * 2. Close connection (closes socket, stops threads)
     * 3. Update stats
     */
    private fun closeFlow(key: TcpFlowKey) {
        val connection = flows.remove(key)
        if (connection != null) {
            connection.close()
            stats.activeFlowCount.decrementAndGet()
            stats.totalFlowsClosed.incrementAndGet()
            Log.v(TAG, "Flow closed: $key")
        }
    }

    /**
     * Close all active flows.
     *
     * Called when VPN stops.
     */
    fun closeAllFlows() {
        Log.i(TAG, "Closing all flows (${flows.size} active)")

        val keys = flows.keys.toList()
        for (key in keys) {
            closeFlow(key)
        }

        executor.shutdown()

        Log.i(TAG, "All flows closed")
    }

    /**
     * Get current statistics.
     */
    fun getStats(): TcpStats = stats
}
