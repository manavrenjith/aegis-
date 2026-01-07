package com.example.betaaegis.vpn.dns

import android.util.Log
import java.nio.ByteBuffer

/**
 * Phase 4: DNS Inspector (Read-Only)
 *
 * Parses DNS queries and responses from UDP packets.
 *
 * CRITICAL RULES:
 * - DNS inspection NEVER blocks forwarding
 * - Parsing failures are silently ignored
 * - This is observation only, not enforcement
 * - DNS packets are forwarded normally regardless of parsing success
 *
 * SCOPE:
 * - DNS over UDP (port 53) only
 * - No DoT (DNS over TLS)
 * - No DoH (DNS over HTTPS)
 *
 * PARSING:
 * - Minimal DNS message structure parsing
 * - Extract: transaction ID, domain name, query type
 * - Extract: response records with TTL and IP
 * - Ignore: EDNS, DNSSEC, and advanced features
 */
object DnsInspector {

    private const val TAG = "DnsInspector"

    /**
     * Check if UDP packet is DNS (port 53).
     */
    fun isDns(destPort: Int): Boolean {
        return destPort == 53
    }

    /**
     * Try to parse DNS query from UDP payload.
     *
     * Returns null if parsing fails (silently).
     * Parsing failures MUST NOT affect forwarding.
     *
     * @param payload UDP payload bytes
     * @return DnsQuery or null
     */
    fun parseQuery(payload: ByteArray): DnsQuery? {
        return try {
            if (payload.size < 12) return null // DNS header is 12 bytes

            val buffer = ByteBuffer.wrap(payload)

            // Parse DNS header
            val transactionId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF

            // Check if this is a query (QR bit = 0)
            val isQuery = (flags and 0x8000) == 0
            if (!isQuery) return null

            val questionCount = buffer.short.toInt() and 0xFFFF
            if (questionCount == 0) return null

            // Skip authority/additional counts
            buffer.position(buffer.position() + 4)

            // Parse first question
            val domain = parseDomainName(buffer) ?: return null
            val queryType = DnsQueryType.fromValue(buffer.short.toInt() and 0xFFFF)

            DnsQuery(
                transactionId = transactionId,
                domain = domain,
                queryType = queryType
            )

        } catch (e: Exception) {
            // Silently ignore parsing errors
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Failed to parse DNS query: ${e.message}")
            }
            null
        }
    }

    /**
     * Try to parse DNS response from UDP payload.
     *
     * Returns null if parsing fails (silently).
     * Parsing failures MUST NOT affect forwarding.
     *
     * @param payload UDP payload bytes
     * @return DnsResponse or null
     */
    fun parseResponse(payload: ByteArray): DnsResponse? {
        return try {
            if (payload.size < 12) return null

            val buffer = ByteBuffer.wrap(payload)

            // Parse DNS header
            val transactionId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF

            // Check if this is a response (QR bit = 1)
            val isResponse = (flags and 0x8000) != 0
            if (!isResponse) return null

            val questionCount = buffer.short.toInt() and 0xFFFF
            val answerCount = buffer.short.toInt() and 0xFFFF

            // Skip authority/additional counts
            buffer.position(buffer.position() + 4)

            // Skip questions
            repeat(questionCount) {
                parseDomainName(buffer) ?: return null
                buffer.position(buffer.position() + 4) // Skip type + class
            }

            // Parse answers
            val records = mutableListOf<DnsRecord>()
            repeat(answerCount) {
                val record = parseResourceRecord(buffer)
                if (record != null) {
                    records.add(record)
                }
            }

            DnsResponse(
                transactionId = transactionId,
                records = records
            )

        } catch (e: Exception) {
            // Silently ignore parsing errors
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Failed to parse DNS response: ${e.message}")
            }
            null
        }
    }

    /**
     * Parse a DNS domain name using label encoding.
     *
     * Handles:
     * - Standard labels
     * - Compression pointers (0xC0)
     *
     * Returns null on error.
     */
    private fun parseDomainName(buffer: ByteBuffer): String? {
        return try {
            val labels = mutableListOf<String>()
            var jumped = false
            var jumpPosition = -1
            var maxJumps = 5 // Prevent infinite loops

            while (true) {
                if (maxJumps-- <= 0) return null

                val length = buffer.get().toInt() and 0xFF

                when {
                    length == 0 -> {
                        // End of name
                        break
                    }
                    (length and 0xC0) == 0xC0 -> {
                        // Compression pointer
                        if (!jumped) {
                            jumpPosition = buffer.position() + 1
                        }
                        val pointer = ((length and 0x3F) shl 8) or (buffer.get().toInt() and 0xFF)
                        buffer.position(pointer)
                        jumped = true
                    }
                    else -> {
                        // Standard label
                        val labelBytes = ByteArray(length)
                        buffer.get(labelBytes)
                        labels.add(String(labelBytes, Charsets.US_ASCII))
                    }
                }
            }

            // Restore position after jump
            if (jumped && jumpPosition > 0) {
                buffer.position(jumpPosition)
            }

            if (labels.isEmpty()) null else labels.joinToString(".")

        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a DNS resource record.
     *
     * Extracts: domain, type, TTL, and IP address (for A/AAAA).
     *
     * Returns null on error.
     */
    private fun parseResourceRecord(buffer: ByteBuffer): DnsRecord? {
        return try {
            val domain = parseDomainName(buffer) ?: return null

            val type = buffer.short.toInt() and 0xFFFF
            val queryType = DnsQueryType.fromValue(type)

            buffer.position(buffer.position() + 2) // Skip class

            val ttl = buffer.int
            val dataLength = buffer.short.toInt() and 0xFFFF

            val ipAddress = when (queryType) {
                DnsQueryType.A -> {
                    // IPv4 address (4 bytes)
                    if (dataLength == 4) {
                        "${buffer.get().toInt() and 0xFF}." +
                        "${buffer.get().toInt() and 0xFF}." +
                        "${buffer.get().toInt() and 0xFF}." +
                        "${buffer.get().toInt() and 0xFF}"
                    } else {
                        buffer.position(buffer.position() + dataLength)
                        null
                    }
                }
                DnsQueryType.AAAA -> {
                    // IPv6 address (16 bytes) - parse as hex groups
                    if (dataLength == 16) {
                        val groups = mutableListOf<String>()
                        repeat(8) {
                            val group = buffer.short.toInt() and 0xFFFF
                            groups.add(group.toString(16))
                        }
                        groups.joinToString(":")
                    } else {
                        buffer.position(buffer.position() + dataLength)
                        null
                    }
                }
                else -> {
                    // Skip other record types
                    buffer.position(buffer.position() + dataLength)
                    null
                }
            }

            DnsRecord(
                domain = domain,
                queryType = queryType,
                ttl = ttl,
                ipAddress = ipAddress
            )

        } catch (e: Exception) {
            null
        }
    }
}

