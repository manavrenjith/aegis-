package com.example.betaaegis.vpn.dns

/**
 * Phase 4: DNS Message Data Classes
 *
 * Simple representation of DNS queries and responses.
 * Read-only, minimal parsing.
 *
 * Scope: DNS over UDP (port 53) only
 * - No DoT (DNS over TLS)
 * - No DoH (DNS over HTTPS)
 */

/**
 * DNS Query Type
 */
enum class DnsQueryType(val value: Int) {
    A(1),       // IPv4 address
    AAAA(28),   // IPv6 address
    CNAME(5),   // Canonical name
    OTHER(0);   // All other types

    companion object {
        fun fromValue(value: Int): DnsQueryType {
            return values().firstOrNull { it.value == value } ?: OTHER
        }
    }
}

/**
 * Parsed DNS Query
 */
data class DnsQuery(
    val transactionId: Int,
    val domain: String,
    val queryType: DnsQueryType
)

/**
 * Parsed DNS Response Record (simplified)
 */
data class DnsRecord(
    val domain: String,
    val queryType: DnsQueryType,
    val ttl: Int,           // Time to live in seconds
    val ipAddress: String?  // Only for A/AAAA records
)

/**
 * Parsed DNS Response
 */
data class DnsResponse(
    val transactionId: Int,
    val records: List<DnsRecord>
)

