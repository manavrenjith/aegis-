package com.example.betaaegis.vpn.dns

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 4: Domain Cache
 *
 * Maps IP addresses to domain names based on DNS responses.
 *
 * CRITICAL RULES:
 * - Domain attribution is best-effort
 * - Missing domains default to null
 * - Cache failures MUST NOT affect forwarding
 * - TTL-based expiration (passive cleanup)
 *
 * OWNERSHIP:
 * - This is control-plane only
 * - No data-plane impact
 * - Read-only for flow creation
 */
class DomainCache {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    companion object {
        private const val TAG = "DomainCache"
        private const val MAX_TTL_SECONDS = 3600 // 1 hour max
        private const val MIN_TTL_SECONDS = 30   // 30 seconds min
    }

    /**
     * Cache entry with expiration.
     */
    private data class CacheEntry(
        val domain: String,
        val expiresAt: Long // System.currentTimeMillis()
    )

    /**
     * Store IP → domain mapping from DNS response.
     *
     * @param ipAddress IP address (IPv4 or IPv6)
     * @param domain Domain name
     * @param ttl TTL from DNS record (seconds)
     */
    fun put(ipAddress: String, domain: String, ttl: Int) {
        try {
            // Normalize TTL to reasonable bounds
            val normalizedTtl = ttl.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
            val expiresAt = System.currentTimeMillis() + (normalizedTtl * 1000L)

            cache[ipAddress] = CacheEntry(domain, expiresAt)

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Cached: $ipAddress -> $domain (TTL: ${normalizedTtl}s)")
            }

        } catch (e: Exception) {
            // Cache errors must not affect operation
            Log.w(TAG, "Failed to cache domain: ${e.message}")
        }
    }

    /**
     * Lookup domain for an IP address.
     *
     * Returns null if:
     * - IP not in cache
     * - Entry has expired
     * - Any error occurs
     *
     * @param ipAddress IP address (IPv4 or IPv6)
     * @return Domain name or null
     */
    fun get(ipAddress: String): String? {
        return try {
            val entry = cache[ipAddress] ?: return null

            // Check if expired
            if (System.currentTimeMillis() > entry.expiresAt) {
                cache.remove(ipAddress)
                return null
            }

            entry.domain

        } catch (e: Exception) {
            // Lookup errors must not affect operation
            Log.w(TAG, "Failed to lookup domain: ${e.message}")
            null
        }
    }

    /**
     * Cleanup expired entries (passive cleanup).
     *
     * Can be called periodically, but not required for correctness.
     */
    fun cleanup() {
        try {
            val now = System.currentTimeMillis()
            val expired = cache.entries.filter { it.value.expiresAt < now }

            expired.forEach { cache.remove(it.key) }

            if (expired.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${expired.size} expired entries")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error: ${e.message}")
        }
    }

    /**
     * Clear all cached entries.
     */
    fun clear() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Get cache statistics.
     */
    fun getStats(): String {
        return "Domain cache: ${cache.size} entries"
    }
}

