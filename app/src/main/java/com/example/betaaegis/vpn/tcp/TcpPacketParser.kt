package com.example.betaaegis.vpn.tcp

import java.nio.ByteBuffer

/**
 * Phase 2: TCP Packet Parser
 *
 * Extracts metadata from raw IP/TCP packets read from TUN interface.
 *
 * This parser is intentionally simple:
 * - Extracts only what's needed for stream forwarding
 * - Handles IPv4 only (IPv6 support deferred)
 * - Minimal error checking (malformed packets are handled gracefully)
 * - No TCP options parsing
 * - No checksum validation
 */
object TcpPacketParser {

    /**
     * Parse a raw IP packet containing TCP.
     *
     * @param packet Raw bytes from TUN interface
     * @return TcpMetadata containing extracted fields
     * @throws IllegalArgumentException if packet is too short or not TCP
     */
    fun parse(packet: ByteArray): TcpMetadata {
        if (packet.size < 40) {
            throw IllegalArgumentException("Packet too short: ${packet.size} bytes")
        }

        val buffer = ByteBuffer.wrap(packet)

        // IP Header parsing
        val versionIhl = buffer.get().toInt() and 0xFF
        val version = (versionIhl shr 4) and 0x0F
        val ihl = (versionIhl and 0x0F) * 4 // IP header length in bytes

        if (version != 4) {
            throw IllegalArgumentException("Not IPv4: version=$version")
        }

        if (packet.size < ihl + 20) {
            throw IllegalArgumentException("Packet too short for TCP")
        }

        // Skip to source IP (offset 12)
        buffer.position(12)
        val srcIp = readIpAddress(buffer)
        val destIp = readIpAddress(buffer)

        // Verify protocol is TCP
        buffer.position(9)
        val protocol = buffer.get().toInt() and 0xFF
        if (protocol != 6) {
            throw IllegalArgumentException("Not TCP: protocol=$protocol")
        }

        // TCP Header parsing
        buffer.position(ihl)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val destPort = buffer.short.toInt() and 0xFFFF
        val seqNum = buffer.int.toLong() and 0xFFFFFFFFL
        val ackNum = buffer.int.toLong() and 0xFFFFFFFFL

        val dataOffsetFlags = buffer.short.toInt() and 0xFFFF
        val dataOffset = ((dataOffsetFlags shr 12) and 0x0F) * 4 // TCP header length
        val flags = dataOffsetFlags and 0xFF

        // Extract TCP flags
        val isFin = (flags and 0x01) != 0
        val isSyn = (flags and 0x02) != 0
        val isRst = (flags and 0x04) != 0
        val isPsh = (flags and 0x08) != 0
        val isAck = (flags and 0x10) != 0

        // Extract payload
        val payloadStart = ihl + dataOffset
        val payload = if (payloadStart < packet.size) {
            packet.copyOfRange(payloadStart, packet.size)
        } else {
            byteArrayOf()
        }

        return TcpMetadata(
            srcIp = srcIp,
            srcPort = srcPort,
            destIp = destIp,
            destPort = destPort,
            seqNum = seqNum,
            ackNum = ackNum,
            isSyn = isSyn,
            isAck = isAck,
            isFin = isFin,
            isRst = isRst,
            isPsh = isPsh,
            payload = payload
        )
    }

    /**
     * Read IPv4 address from buffer (4 bytes).
     */
    private fun readIpAddress(buffer: ByteBuffer): String {
        return "${buffer.get().toInt() and 0xFF}." +
               "${buffer.get().toInt() and 0xFF}." +
               "${buffer.get().toInt() and 0xFF}." +
               "${buffer.get().toInt() and 0xFF}"
    }
}

/**
 * Parsed TCP packet metadata.
 *
 * Contains only the fields needed for stream forwarding.
 */
data class TcpMetadata(
    val srcIp: String,
    val srcPort: Int,
    val destIp: String,
    val destPort: Int,
    val seqNum: Long,
    val ackNum: Long,
    val isSyn: Boolean,
    val isAck: Boolean,
    val isFin: Boolean,
    val isRst: Boolean,
    val isPsh: Boolean,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TcpMetadata
        return srcIp == other.srcIp && srcPort == other.srcPort &&
               destIp == other.destIp && destPort == other.destPort
    }

    override fun hashCode(): Int {
        var result = srcIp.hashCode()
        result = 31 * result + srcPort
        result = 31 * result + destIp.hashCode()
        result = 31 * result + destPort
        return result
    }
}

