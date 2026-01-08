package com.example.betaaegis.vpn.udp

import android.net.VpnService
import android.util.Log
import com.example.betaaegis.telemetry.UdpStatsSnapshot
import com.example.betaaegis.vpn.dns.DnsInspector
import com.example.betaaegis.vpn.dns.DomainCache
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
 * Phase 4: DNS Inspection Added
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
 * PHASE 4: DNS INSPECTION (READ-ONLY)
 * - Inspect DNS queries and responses on port 53
 * - Cache IP → domain mappings
 * - DNS inspection NEVER blocks forwarding
 * - Parsing failures are silently ignored
 *
 * ENFORCEMENT:
 * - Blocked flows never create UdpConnection
 * - App times out naturally
 * - No packet injection
 */
class UdpForwarder(
    private val vpnService: VpnService,
    private val tunOutputStream: FileOutputStream,
    private val ruleEngine: RuleEngine,
    private val domainCache: DomainCache // Phase 4
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
        private const val IDLE_TIMEOUT_MS = 120_000L // 2 minutes (WhatsApp-safe)
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
     * This is the ONLY path for UDP packets in Phase 3/4.
     *
     * Phase 4: Inspect DNS packets for domain attribution.
     *
     * @param packet Raw IP/UDP packet from TUN
     */
    fun handleUdpPacket(packet: ByteArray) {
        try {
            val metadata = UdpPacketParser.parse(packet)

            // Phase 4: DNS Inspection (Read-Only)
            // This NEVER blocks forwarding
            if (DnsInspector.isDns(metadata.destPort)) {
                inspectDnsQuery(metadata)
            } else if (metadata.srcPort == 53) {
                // DNS response
                inspectDnsResponse(metadata)
            }

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
     * Phase 4: Inspect DNS query (read-only).
     * Phase 5: Record observation for telemetry.
     *
     * Parsing failures are silently ignored.
     * This MUST NOT affect forwarding.
     */
    private fun inspectDnsQuery(metadata: UdpMetadata) {
        try {
            val query = DnsInspector.parseQuery(metadata.payload)
            if (query != null) {
                // Phase 5: Record observation (non-blocking, safe)
                domainCache.recordQuery()

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "DNS Query: ${query.domain} (${query.queryType})")
                }
            }
        } catch (e: Exception) {
            // Silently ignore - inspection is best-effort
        }
    }

    /**
     * Phase 4: Inspect DNS response and cache domains (read-only).
     * Phase 5: Record observation for telemetry.
     *
     * Parsing failures are silently ignored.
     * This MUST NOT affect forwarding.
     */
    private fun inspectDnsResponse(metadata: UdpMetadata) {
        try {
            val response = DnsInspector.parseResponse(metadata.payload)
            if (response != null) {
                // Phase 5: Record observation (non-blocking, safe)
                domainCache.recordResponse()

                // Cache all A/AAAA records
                response.records.forEach { record ->
                    if (record.ipAddress != null) {
                        domainCache.put(record.ipAddress, record.domain, record.ttl)

                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "DNS Response: ${record.domain} -> ${record.ipAddress} (TTL: ${record.ttl}s)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently ignore - inspection is best-effort
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
     *
     * Phase 4: Lookup domain from cache for policy evaluation.
     */
    private fun createFlow(key: UdpFlowKey, metadata: UdpMetadata): UdpConnection? {
        // Phase 4: Lookup domain for destination IP (best-effort)
        val domain = domainCache.get(metadata.destIp)

        // Evaluate policy (ONCE per flow)
        val decision = ruleEngine.evaluate(
            protocol = "udp",
            srcIp = metadata.srcIp,
            srcPort = metadata.srcPort,
            destIp = metadata.destIp,
            destPort = metadata.destPort,
            domain = domain // Phase 4: Pass domain to policy
        )

        if (decision == FlowDecision.BLOCK) {
            Log.d(TAG, "UDP flow blocked: $key (domain: $domain)")
            stats.totalFlowsBlocked.incrementAndGet()
            // Return null - no connection object created
            // App will timeout naturally
            return null
        }

        // ALLOW: Create connection
        Log.d(TAG, "New UDP flow: $key (domain: $domain)")

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

    /**
     * Phase 5: Get statistics snapshot for observability.
     *
     * SAFETY:
     * - Read-only operation
     * - Never blocks forwarding
     * - Safe to call from UI thread
     */
    fun getStatsSnapshot(): UdpStatsSnapshot {
        return UdpStatsSnapshot(
            bytesUplink = stats.bytesUplink.get(),
            bytesDownlink = stats.bytesDownlink.get(),
            activeFlowCount = stats.activeFlowCount.get(),
            totalFlowsCreated = stats.totalFlowsCreated.get(),
            totalFlowsClosed = stats.totalFlowsClosed.get(),
            totalFlowsBlocked = stats.totalFlowsBlocked.get()
        )
    }
}


