package com.example.betaaegis.vpn.tcp

import android.util.Log
import com.example.betaaegis.telemetry.TcpStatsSnapshot
import com.example.betaaegis.vpn.AegisVpnService
import com.example.betaaegis.vpn.dns.DomainCache
import com.example.betaaegis.vpn.policy.FlowDecision
import com.example.betaaegis.vpn.policy.RuleEngine
import com.example.betaaegis.vpn.tcp.proxy.TcpProxyEngine
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 2: TCP Stream Forwarder
 * Phase 3: Policy Integration Added
 * Phase 4: Domain-Based Policy Added
 *
 * NOTE (Phase 0):
 * This class implements packet-based TCP forwarding with socket-based stream handling.
 * It is intentionally preserved for debugging, comparison, and rollback purposes
 * while a TCP proxy is developed in parallel.
 *
 * DO NOT refactor or extend this class.
 * All new TCP features should target the future TCP proxy implementation.
 *
 * This implementation will remain frozen and will be conditionally executed
 * based on TcpMode.USE_TCP_PROXY flag.
 *
 * Phase 1: Added TcpProxyEngine integration (passive, guarded by feature flag)
 */
class TcpForwarder(
    private val vpnService: AegisVpnService,
    private val tunOutputStream: FileOutputStream,
    private val ruleEngine: RuleEngine? = null, // Phase 3: Optional policy engine
    private val domainCache: DomainCache? = null // Phase 4: Optional domain cache
) {
    private val flows = ConcurrentHashMap<TcpFlowKey, TcpConnection>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            name = "TcpForwarder-Worker"
            isDaemon = true
        }
    }
    private val stats = TcpStats()

    // Phase 2: TCP Proxy Engine (active handshake, requires TUN output stream)
    private val tcpProxyEngine = TcpProxyEngine(vpnService, tunOutputStream)

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
     * NetGuard-grade packet handling:
     * - NEVER send RST in ESTABLISHED unless provably invalid
     * - Accept all ACK packets in ESTABLISHED
     * - Handle FIN transitions correctly
     * - Only send RST for truly unknown/invalid flows
     *
     * Phase 1: Added TCP proxy routing (guarded, passive)
     */
    fun handleTcpPacket(packet: ByteArray) {
        // Phase 1: Guard rail for TCP proxy
        // When USE_TCP_PROXY is true, route to TcpProxyEngine instead of legacy path
        // Phase 1: Proxy is observation-only, does not forward traffic
        if (TcpMode.USE_TCP_PROXY) {
            try {
                val metadata = TcpPacketParser.parse(packet)
                tcpProxyEngine.handlePacket(metadata)
            } catch (e: Exception) {
                Log.w(TAG, "TCP proxy packet handling failed: ${e.message}")
            }
            return
        }

        // Legacy packet-based TCP forwarding (frozen implementation)
        try {
            val metadata = TcpPacketParser.parse(packet)

            val flowKey = TcpFlowKey(
                metadata.srcIp,
                metadata.srcPort,
                metadata.destIp,
                metadata.destPort
            )

            // Check if flow exists
            val connection = flows[flowKey]

            when {
                // RST from app - always handle
                metadata.isRst -> {
                    if (connection != null) {
                        connection.handleRst()
                        closeFlow(flowKey)
                    }
                }

                // SYN (new connection)
                metadata.isSyn && !metadata.isAck -> {
                    if (connection == null) {
                        // New connection request
                        handleNewConnection(flowKey, metadata)
                    } else {
                        // Duplicate SYN for existing flow - ignore
                        Log.v(TAG, "Duplicate SYN for existing flow: $flowKey")
                    }
                }

                // FIN from app
                metadata.isFin -> {
                    if (connection != null) {
                        connection.handleAppFin()
                        // Check if should close
                        if (connection.getState() == TcpFlowState.TIME_WAIT) {
                            closeFlow(flowKey)
                        }
                    } else {
                        // FIN for unknown flow - send RST
                        Log.d(TAG, "FIN for unknown flow: $flowKey - sending RST")
                        sendRstForKey(flowKey, metadata.seqNum, metadata.ackNum)
                    }
                }

                // ACK or data packet
                metadata.isAck -> {
                    if (connection != null) {
                        val state = connection.getState()

                        when (state) {
                            TcpFlowState.ESTABLISHED -> {
                                // NetGuard-grade FAIL-OPEN behavior:
                                // - Accept ALL ACK packets in ESTABLISHED
                                // - Accept ALL payload (TLS ServerHello, certificates, data)
                                // - NEVER send RST
                                // - NEVER treat packets as protocol violations
                                // - Let app and server negotiate correctness naturally
                                connection.handleEstablishedPacket(metadata)

                                // Update stats if payload present
                                if (metadata.payload.isNotEmpty()) {
                                    stats.bytesUplink.addAndGet(metadata.payload.size.toLong())
                                }
                            }
                            TcpFlowState.FIN_WAIT_APP,
                            TcpFlowState.FIN_WAIT_SERVER,
                            TcpFlowState.TIME_WAIT -> {
                                // Accept ACKs and data in FIN states
                                // Still forwarding data during graceful close
                                if (metadata.payload.isNotEmpty()) {
                                    connection.sendToServer(metadata.payload)
                                    stats.bytesUplink.addAndGet(metadata.payload.size.toLong())
                                }
                            }
                            TcpFlowState.SYN_SENT -> {
                                // Packet arrived before handshake complete
                                // Ignore silently - app might be sending early data
                                // DO NOT send RST
                            }
                            else -> {
                                // Packet in CLOSED/RESET state - ignore silently
                                // DO NOT send RST
                            }
                        }
                    } else {
                        // ACK/data for unknown flow
                        // ONLY send RST if this is truly a new connection attempt
                        // Do NOT send RST for stray packets that might be from recently closed flows
                        if (metadata.payload.isNotEmpty() && !metadata.isSyn) {
                            Log.d(TAG, "Packet for unknown flow: $flowKey payload=${metadata.payload.size} - sending RST")
                            sendRstForKey(flowKey, metadata.seqNum, metadata.ackNum)
                        }
                    }
                }

                else -> {
                    // Other packet types - ignore
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
     * Phase 4: Domain lookup for policy evaluation
     *
     * CONCURRENCY FIX:
     * Uses putIfAbsent() for atomic flow creation to prevent races
     * when multiple SYN packets arrive simultaneously.
     */
    private fun handleNewConnection(key: TcpFlowKey, metadata: TcpMetadata) {
        if (!vpnService.isVpnRunning()) {
            Log.d(TAG, "Ignoring new connection after VPN stop: $key")
            return
        }

        // Phase 4: Lookup domain for destination IP (best-effort)
        val domain = domainCache?.get(key.destIp)

        // Phase 3: Evaluate policy if engine is available
        if (ruleEngine != null) {
            val decision = ruleEngine.evaluate(
                protocol = "tcp",
                srcIp = key.srcIp,
                srcPort = key.srcPort,
                destIp = key.destIp,
                destPort = key.destPort,
                domain = domain // Phase 4: Pass domain to policy
            )

            if (decision == FlowDecision.BLOCK) {
                Log.d(TAG, "TCP flow blocked by policy: $key (domain: $domain)")
                // Send RST to app (connection rejected)
                sendRstForKey(key, metadata.seqNum + 1, metadata.ackNum)
                return
            }
        }

        // Create connection object
        val connection = TcpConnection(
            key = key,
            vpnService = vpnService,
            tunOutputStream = tunOutputStream
        )

        // ATOMIC INSERTION: Use putIfAbsent to prevent race conditions
        val existing = flows.putIfAbsent(key, connection)

        if (existing != null) {
            // Another thread won the race - duplicate SYN
            Log.d(TAG, "Duplicate SYN for existing flow: $key")
            connection.close()
            return
        }

        // We won the race - this is the authoritative flow
        Log.d(TAG, "New connection: $key (domain: $domain)")

        stats.activeFlowCount.incrementAndGet()
        stats.totalFlowsCreated.incrementAndGet()

        // Asynchronously connect to destination
        executor.execute {
            try {
                // Connect to remote server (socket is protected inside)
                connection.connect()

                // Send SYN-ACK to app (transitions to ESTABLISHED)
                connection.sendSynAck()

                // Start bidirectional forwarding
                connection.startForwarding(stats.bytesDownlink)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed for $key: ${e.message}", e)

                // Send RST to app (connection rejected)
                sendRstForKey(key, metadata.seqNum + 1, metadata.ackNum)

                // Clean up
                closeFlow(key)
            }
        }
    }

    /**
     * Send RST for a flow key (when no connection exists or rejected).
     *
     * IMPORTANT: Only call this when RST is truly required:
     * - Unknown flow receiving data
     * - Policy-blocked connection
     * - Connection setup failure
     *
     * DO NOT call for:
     * - Expected ESTABLISHED packets
     * - TLS data
     * - Reordered packets
     */
    private fun sendRstForKey(key: TcpFlowKey, seqNum: Long, ackNum: Long) {
        try {
            Log.d(TAG, "Sending RST: $key seq=$seqNum ack=$ackNum")

            val packet = TcpPacketBuilder.build(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                flags = 0x04, // RST
                seqNum = ackNum, // RST uses ack as seq
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
     *
     * Phase 1: Also shutdown TCP proxy engine
     */
    fun closeAllFlows() {
        Log.i(TAG, "Closing all flows (${flows.size} active)")

        // Phase 1: Shutdown TCP proxy engine
        tcpProxyEngine.shutdown()

        val keys = flows.keys.toList()
        for (key in keys) {
            closeFlow(key)
        }

        try {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for executor shutdown")
        }

        Log.i(TAG, "All flows closed")
    }

    /**
     * Get current statistics.
     */
    fun getStats(): TcpStats = stats

    /**
     * Phase 5: Get statistics snapshot for observability.
     *
     * SAFETY:
     * - Read-only operation
     * - Never blocks forwarding
     * - Safe to call from UI thread
     */
    fun getStatsSnapshot(): TcpStatsSnapshot {
        return TcpStatsSnapshot(
            bytesUplink = stats.bytesUplink.get(),
            bytesDownlink = stats.bytesDownlink.get(),
            activeFlowCount = stats.activeFlowCount.get(),
            totalFlowsCreated = stats.totalFlowsCreated.get(),
            totalFlowsClosed = stats.totalFlowsClosed.get()
        )
    }
}

