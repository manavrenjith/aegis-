package com.example.betaaegis.telemetry


/**
 * Phase 5: VPN Statistics Aggregator (Read-Only)
 *
 * PURPOSE:
 * - Aggregate statistics from all VPN components
 * - Provide unified view for UI
 * - ZERO impact on data plane
 *
 * SAFETY RULES:
 * - All operations are read-only
 * - Thread-safe atomic access
 * - Never blocks forwarding
 * - Failures do not affect VPN
 *
 * DATA SOURCES:
 * - VpnTelemetry (packet/byte counts)
 * - TcpForwarder stats
 * - UdpForwarder stats
 * - RuleEngine decision counts
 * - DomainCache size
 */
data class VpnStatistics(
    // Overall traffic
    val totalPackets: Long = 0,
    val totalBytes: Long = 0,
    val lastPacketTimestamp: Long = 0,

    // TCP stats
    val tcpBytesUplink: Long = 0,
    val tcpBytesDownlink: Long = 0,
    val tcpActiveFlows: Int = 0,
    val tcpTotalFlowsCreated: Long = 0,
    val tcpTotalFlowsClosed: Long = 0,

    // UDP stats
    val udpBytesUplink: Long = 0,
    val udpBytesDownlink: Long = 0,
    val udpActiveFlows: Int = 0,
    val udpTotalFlowsCreated: Long = 0,
    val udpTotalFlowsClosed: Long = 0,
    val udpTotalFlowsBlocked: Long = 0,

    // DNS/Domain stats (Phase 4)
    val dnsCacheSize: Int = 0,
    val dnsQueriesObserved: Long = 0,
    val dnsResponsesObserved: Long = 0,

    // Policy stats
    val totalFlowsBlocked: Long = 0,
    val totalFlowsAllowed: Long = 0
) {
    /**
     * Calculate total bytes across TCP and UDP.
     */
    fun getTotalDataTransferred(): Long {
        return tcpBytesUplink + tcpBytesDownlink + udpBytesUplink + udpBytesDownlink
    }

    /**
     * Calculate total active flows.
     */
    fun getTotalActiveFlows(): Int {
        return tcpActiveFlows + udpActiveFlows
    }

    /**
     * Format bytes in human-readable form.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * Get human-readable summary.
     */
    fun getSummary(): String {
        return """
            Total Packets: $totalPackets
            Total Data: ${formatBytes(getTotalDataTransferred())}
            Active Flows: ${getTotalActiveFlows()} (TCP: $tcpActiveFlows, UDP: $udpActiveFlows)
            Flows Blocked: $totalFlowsBlocked
            DNS Cache: $dnsCacheSize domains
        """.trimIndent()
    }
}

/**
 * Statistics collector that safely reads from VPN components.
 *
 * THREAD-SAFETY:
 * - All reads are atomic
 * - Snapshot may be inconsistent across components
 * - Individual values are always valid
 *
 * FAILURE HANDLING:
 * - Null components return zero values
 * - Exceptions caught and logged
 * - Never propagates errors to caller
 */
class VpnStatisticsCollector {

    /**
     * Collect current statistics snapshot.
     *
     * This method is SAFE to call from UI thread.
     * It never blocks on I/O or locks.
     *
     * @param components Map of component name to stats retrieval function
     * @return VpnStatistics snapshot
     */
    fun collectStatistics(
        getVpnTelemetry: (() -> VpnTelemetrySnapshot)? = null,
        getTcpStats: (() -> TcpStatsSnapshot)? = null,
        getUdpStats: (() -> UdpStatsSnapshot)? = null,
        getDnsStats: (() -> DnsStatsSnapshot)? = null
    ): VpnStatistics {
        return try {
            val vpnTelem = getVpnTelemetry?.invoke()
            val tcpStats = getTcpStats?.invoke()
            val udpStats = getUdpStats?.invoke()
            val dnsStats = getDnsStats?.invoke()

            VpnStatistics(
                totalPackets = vpnTelem?.packetCount ?: 0,
                totalBytes = vpnTelem?.byteCount ?: 0,
                lastPacketTimestamp = vpnTelem?.lastPacketTimestamp ?: 0,

                tcpBytesUplink = tcpStats?.bytesUplink ?: 0,
                tcpBytesDownlink = tcpStats?.bytesDownlink ?: 0,
                tcpActiveFlows = tcpStats?.activeFlowCount ?: 0,
                tcpTotalFlowsCreated = tcpStats?.totalFlowsCreated ?: 0,
                tcpTotalFlowsClosed = tcpStats?.totalFlowsClosed ?: 0,

                udpBytesUplink = udpStats?.bytesUplink ?: 0,
                udpBytesDownlink = udpStats?.bytesDownlink ?: 0,
                udpActiveFlows = udpStats?.activeFlowCount ?: 0,
                udpTotalFlowsCreated = udpStats?.totalFlowsCreated ?: 0,
                udpTotalFlowsClosed = udpStats?.totalFlowsClosed ?: 0,
                udpTotalFlowsBlocked = udpStats?.totalFlowsBlocked ?: 0,

                dnsCacheSize = dnsStats?.cacheSize ?: 0,
                dnsQueriesObserved = dnsStats?.queriesObserved ?: 0,
                dnsResponsesObserved = dnsStats?.responsesObserved ?: 0,

                totalFlowsBlocked = (udpStats?.totalFlowsBlocked ?: 0),
                totalFlowsAllowed = (tcpStats?.totalFlowsCreated ?: 0) + (udpStats?.totalFlowsCreated ?: 0)
            )
        } catch (e: Exception) {
            // Never propagate errors - return empty stats
            VpnStatistics()
        }
    }
}

/**
 * Snapshot data classes for safe statistics retrieval.
 */
data class VpnTelemetrySnapshot(
    val packetCount: Long,
    val byteCount: Long,
    val lastPacketTimestamp: Long
)

data class TcpStatsSnapshot(
    val bytesUplink: Long,
    val bytesDownlink: Long,
    val activeFlowCount: Int,
    val totalFlowsCreated: Long,
    val totalFlowsClosed: Long
)

data class UdpStatsSnapshot(
    val bytesUplink: Long,
    val bytesDownlink: Long,
    val activeFlowCount: Int,
    val totalFlowsCreated: Long,
    val totalFlowsClosed: Long,
    val totalFlowsBlocked: Long
)

data class DnsStatsSnapshot(
    val cacheSize: Int,
    val queriesObserved: Long,
    val responsesObserved: Long
)

