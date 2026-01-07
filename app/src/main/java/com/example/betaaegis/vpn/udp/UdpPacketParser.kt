package com.example.betaaegis.vpn.udp

import java.nio.ByteBuffer

/**
 * Phase 3: UDP Packet Parser
 *
 * Extracts metadata from raw IP/UDP packets read from TUN interface.
 *
 * Similar to TcpPacketParser but for UDP.
 * - Handles IPv4 only (IPv6 deferred)
 * - Minimal error checking
 * - No checksum validation
 */
object UdpPacketParser {

    /**
     * Parse a raw IP packet containing UDP.
     *
     * @param packet Raw bytes from TUN interface
     * @return UdpMetadata containing extracted fields
     * @throws IllegalArgumentException if packet is too short or not UDP
     */
    fun parse(packet: ByteArray): UdpMetadata {
        if (packet.size < 28) { // IP header (20) + UDP header (8)
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

        if (packet.size < ihl + 8) {
            throw IllegalArgumentException("Packet too short for UDP")
        }

        // Skip to source IP (offset 12)
        buffer.position(12)
        val srcIp = readIpAddress(buffer)
        val destIp = readIpAddress(buffer)

        // Verify protocol is UDP
        buffer.position(9)
        val protocol = buffer.get().toInt() and 0xFF
        if (protocol != 17) {
            throw IllegalArgumentException("Not UDP: protocol=$protocol")
        }

        // UDP Header parsing
        buffer.position(ihl)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val destPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF
        val checksum = buffer.short.toInt() and 0xFFFF

        // Extract payload
        val payloadStart = ihl + 8 // IP header + UDP header
        val payload = if (payloadStart < packet.size) {
            packet.copyOfRange(payloadStart, packet.size)
        } else {
            byteArrayOf()
        }

        return UdpMetadata(
            srcIp = srcIp,
            srcPort = srcPort,
            destIp = destIp,
            destPort = destPort,
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
 * Parsed UDP packet metadata.
 */
data class UdpMetadata(
    val srcIp: String,
    val srcPort: Int,
    val destIp: String,
    val destPort: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UdpMetadata
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

