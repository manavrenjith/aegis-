package com.example.betaaegis.vpn.tcp

import java.nio.ByteBuffer

/**
 * Phase 2: TCP Packet Builder
 *
 * Constructs valid IP/TCP packets with proper checksums for writing back to TUN interface.
 *
 * Why checksum calculation is required:
 * - The kernel expects valid checksums on packets written to TUN
 * - Invalid checksums cause packets to be silently dropped
 * - We must compute both IP header checksum and TCP pseudo-header checksum
 *
 * Why this is simpler than packet-transparent forwarding:
 * - No TCP sequence/ACK tracking needed
 * - No retransmission logic
 * - No window management
 * - Just construct minimal valid packets for data delivery
 */
object TcpPacketBuilder {

    private const val IP_VERSION = 4
    private const val IP_IHL = 5 // 5 * 4 = 20 bytes (no options)
    private const val IP_HEADER_SIZE = 20
    private const val TCP_HEADER_SIZE = 20 // No options
    private const val IP_TTL = 64
    private const val IP_PROTOCOL_TCP = 6

    /**
     * Build a complete IP/TCP packet.
     *
     * @param srcIp Source IP address (dotted notation)
     * @param srcPort Source port
     * @param destIp Destination IP address
     * @param destPort Destination port
     * @param flags TCP flags byte (e.g., 0x18 for PSH+ACK)
     * @param seqNum TCP sequence number
     * @param ackNum TCP acknowledgment number
     * @param payload TCP payload data
     * @return Complete IP packet as byte array
     */
    fun build(
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        flags: Int,
        seqNum: Long,
        ackNum: Long,
        payload: ByteArray
    ): ByteArray {
        val totalSize = IP_HEADER_SIZE + TCP_HEADER_SIZE + payload.size
        val buffer = ByteBuffer.allocate(totalSize)

        // ===== IP Header =====
        buffer.put(((IP_VERSION shl 4) or IP_IHL).toByte()) // Version + IHL
        buffer.put(0) // DSCP/ECN
        buffer.putShort(totalSize.toShort()) // Total length
        buffer.putShort(0) // Identification
        buffer.putShort(0x4000.toShort()) // Flags: Don't Fragment
        buffer.put(IP_TTL.toByte()) // TTL
        buffer.put(IP_PROTOCOL_TCP.toByte()) // Protocol: TCP
        buffer.putShort(0) // Checksum (fill in later)
        putIpAddress(buffer, srcIp) // Source IP
        putIpAddress(buffer, destIp) // Destination IP

        // Calculate and insert IP header checksum
        val ipChecksumPos = 10
        val ipChecksum = calculateChecksum(buffer.array(), 0, IP_HEADER_SIZE)
        buffer.putShort(ipChecksumPos, ipChecksum.toShort())

        // ===== TCP Header =====
        val tcpHeaderStart = IP_HEADER_SIZE
        buffer.position(tcpHeaderStart)

        buffer.putShort(srcPort.toShort()) // Source port
        buffer.putShort(destPort.toShort()) // Destination port
        buffer.putInt(seqNum.toInt()) // Sequence number
        buffer.putInt(ackNum.toInt()) // Acknowledgment number
        buffer.putShort((((TCP_HEADER_SIZE / 4) shl 12) or flags).toShort()) // Data offset + flags
        buffer.putShort(8192) // Window size
        buffer.putShort(0) // Checksum (fill in later)
        buffer.putShort(0) // Urgent pointer

        // ===== Payload =====
        buffer.put(payload)

        // Calculate and insert TCP checksum
        val tcpChecksumPos = tcpHeaderStart + 16
        val tcpChecksum = calculateTcpChecksum(
            buffer.array(),
            srcIp,
            destIp,
            TCP_HEADER_SIZE + payload.size
        )
        buffer.putShort(tcpChecksumPos, tcpChecksum.toShort())

        return buffer.array()
    }

    /**
     * Write IP address to buffer (4 bytes).
     */
    private fun putIpAddress(buffer: ByteBuffer, ip: String) {
        val parts = ip.split(".")
        if (parts.size != 4) {
            throw IllegalArgumentException("Invalid IP address: $ip")
        }
        parts.forEach { buffer.put(it.toInt().toByte()) }
    }

    /**
     * Calculate Internet checksum (RFC 1071).
     *
     * Used for both IP header and TCP pseudo-header.
     */
    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset

        // Sum all 16-bit words
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }

        // Add remaining byte if odd length
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        // Fold 32-bit sum to 16 bits
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        // One's complement
        return (sum.inv().toInt() and 0xFFFF)
    }

    /**
     * Calculate TCP checksum using pseudo-header.
     *
     * The TCP checksum includes a pseudo-header with:
     * - Source IP (4 bytes)
     * - Destination IP (4 bytes)
     * - Protocol (1 byte, padded to 2)
     * - TCP length (2 bytes)
     * - TCP header + payload
     */
    private fun calculateTcpChecksum(
        packet: ByteArray,
        srcIp: String,
        destIp: String,
        tcpLength: Int
    ): Int {
        val pseudoHeaderSize = 12
        val pseudoBuffer = ByteBuffer.allocate(pseudoHeaderSize + tcpLength)

        // Build pseudo-header
        putIpAddress(pseudoBuffer, srcIp)
        putIpAddress(pseudoBuffer, destIp)
        pseudoBuffer.put(0) // Reserved
        pseudoBuffer.put(IP_PROTOCOL_TCP.toByte()) // Protocol
        pseudoBuffer.putShort(tcpLength.toShort()) // TCP length

        // Copy TCP header + payload (starting at IP_HEADER_SIZE in packet)
        pseudoBuffer.put(packet, IP_HEADER_SIZE, tcpLength)

        return calculateChecksum(pseudoBuffer.array(), 0, pseudoBuffer.capacity())
    }
}

