package com.example.betaaegis.vpn.policy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3: UID Resolution (Identity Layer)
 *
 * Determines which app (UID) owns each network flow.
 *
 * REQUIREMENTS:
 * - Best-effort attribution
 * - May be async (not yet implemented)
 * - Never blocks forwarding logic
 * - Unknown UID defaults to -1
 *
 * PHASE 3 IMPLEMENTATION:
 * - Uses /proc/net/tcp and /proc/net/udp
 * - Parses connection table to find UID by 5-tuple
 * - Caches results per flow to avoid repeated lookups
 *
 * NON-GOALS:
 * - Perfect attribution guarantee (impossible on Android)
 * - Blocking while UID is unresolved
 * - Real-time notifications of UID changes
 */
class UidResolver {

    companion object {
        private const val TAG = "UidResolver"
        const val UID_UNKNOWN = -1
        private const val PROC_NET_TCP = "/proc/net/tcp"
        private const val PROC_NET_UDP = "/proc/net/udp"
    }

    // Cache resolved UIDs per connection key
    private val uidCache = ConcurrentHashMap<String, Int>()
    private val stats = Stats()

    /**
     * Resolve UID for a flow.
     *
     * @param protocol "tcp" or "udp"
     * @param srcIp Source IP address
     * @param srcPort Source port
     * @param destIp Destination IP address
     * @param destPort Destination port
     * @return UID (positive integer) or UID_UNKNOWN (-1)
     */
    fun resolveUid(
        protocol: String,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int
    ): Int {
        val key = "$protocol:$srcIp:$srcPort:$destIp:$destPort"

        // Check cache first
        uidCache[key]?.let {
            stats.cacheHits.incrementAndGet()
            return it
        }

        stats.cacheMisses.incrementAndGet()

        // Resolve from /proc
        val uid = when (protocol.lowercase()) {
            "tcp" -> resolveFromProc(PROC_NET_TCP, srcIp, srcPort)
            "udp" -> resolveFromProc(PROC_NET_UDP, srcIp, srcPort)
            else -> {
                Log.w(TAG, "Unknown protocol: $protocol")
                UID_UNKNOWN
            }
        }

        // Cache result (even if unknown, to avoid repeated lookups)
        if (uid != UID_UNKNOWN) {
            uidCache[key] = uid
        }

        return uid
    }

    /**
     * Parse /proc/net/tcp or /proc/net/udp to find UID.
     *
     * Format:
     * sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
     *  0: 0100007F:1F40 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12345 ...
     *
     * We match on local_address (hex IP:port) and extract uid.
     */
    private fun resolveFromProc(procFile: String, srcIp: String, srcPort: Int): Int {
        try {
            val hexLocalAddr = ipPortToHex(srcIp, srcPort)

            java.io.File(procFile).useLines { lines ->
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 8 && parts[0] != "sl") {
                        val localAddr = parts[1]
                        if (localAddr.equals(hexLocalAddr, ignoreCase = true)) {
                            val uid = parts[7].toIntOrNull()
                            if (uid != null) {
                                return uid
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $procFile: ${e.message}")
        }

        return UID_UNKNOWN
    }

    /**
     * Convert IP:port to hex format used in /proc/net/*.
     *
     * Example: "127.0.0.1:8000" -> "0100007F:1F40"
     * (Note: IP is in little-endian format)
     */
    private fun ipPortToHex(ip: String, port: Int): String {
        val parts = ip.split(".")
        if (parts.size != 4) {
            return ""
        }

        // Convert IP to hex (little-endian)
        val hexIp = parts.reversed().joinToString("") { part ->
            part.toInt().toString(16).uppercase().padStart(2, '0')
        }

        // Convert port to hex
        val hexPort = port.toString(16).uppercase().padStart(4, '0')

        return "$hexIp:$hexPort"
    }

    /**
     * Get resolver statistics (for telemetry).
     */
    fun getStats(): String {
        return "UID resolver: cache=${uidCache.size}, hits=${stats.cacheHits.get()}, misses=${stats.cacheMisses.get()}"
    }

    /**
     * Clear cache (e.g., on VPN restart).
     */
    fun clearCache() {
        uidCache.clear()
    }

    private class Stats {
        val cacheHits = AtomicInteger(0)
        val cacheMisses = AtomicInteger(0)
    }
}

