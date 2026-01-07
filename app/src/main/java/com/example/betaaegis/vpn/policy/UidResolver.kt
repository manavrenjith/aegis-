package com.example.betaaegis.vpn.policy
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
class UidResolver {
    companion object {
        private const val TAG = "UidResolver"
        const val UID_UNKNOWN = -1
        private const val PROC_NET_TCP = "/proc/net/tcp"
        private const val PROC_NET_UDP = "/proc/net/udp"
    }
    private val uidCache = ConcurrentHashMap<String, Int>()
    private val stats = Stats()
    fun resolveUid(
        protocol: String,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int
    ): Int {
        val key = "$protocol:$srcIp:$srcPort:$destIp:$destPort"
        uidCache[key]?.let {
            stats.cacheHits.incrementAndGet()
            return it
        }
        stats.cacheMisses.incrementAndGet()
        val uid = when (protocol.lowercase()) {
            "tcp" -> resolveFromProc(PROC_NET_TCP, srcIp, srcPort)
            "udp" -> resolveFromProc(PROC_NET_UDP, srcIp, srcPort)
            else -> {
                Log.w(TAG, "Unknown protocol: $protocol")
                UID_UNKNOWN
            }
        }
        if (uid != UID_UNKNOWN) {
            uidCache[key] = uid
        }
        return uid
    }
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
    private fun ipPortToHex(ip: String, port: Int): String {
        val parts = ip.split(".")
        if (parts.size != 4) {
            return ""
        }
        val hexIp = parts.reversed().joinToString("") { part ->
            part.toInt().toString(16).uppercase().padStart(2, '0')
        }
        val hexPort = port.toString(16).uppercase().padStart(4, '0')
        return "$hexIp:$hexPort"
    }
    fun getStats(): String {
        return "UID resolver: cache=${uidCache.size}, hits=${stats.cacheHits.get()}, misses=${stats.cacheMisses.get()}"
    }
    fun clearCache() {
        uidCache.clear()
    }
    private class Stats {
        val cacheHits = AtomicInteger(0)
        val cacheMisses = AtomicInteger(0)
    }
}