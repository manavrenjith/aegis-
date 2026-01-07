package com.example.betaaegis.vpn.udp

import java.nio.ByteBuffer

/**
 * Phase 3: UDP Packet Builder
 *
 * Constructs UDP packets to write back to TUN interface.
 *
 * Similar to TcpPacketBuilder but simpler (no sequence numbers, flags, etc.).
 * - Builds IP header
 * - Builds UDP header
 * - Calculates checksums
 */
object UdpPacketBuilder {

    /**
     * Build a UDP packet (server response → app).
     *
     * @param srcIp Source IP (server)
     * @param srcPort Source port (server)
     * @param destIp Destination IP (app)
     * @param destPort Destination port (app)
     * @param payload UDP payload
     * @return Complete IP/UDP packet ready to write to TUN
     */
    fun buildPacket(
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + payload.size

        val buffer = ByteBuffer.allocate(totalLength)

        // Build IP header
        buffer.put((0x45).toByte()) // Version 4, IHL 5 (20 bytes)
        buffer.put(0) // DSCP/ECN
        buffer.putShort(totalLength.toShort()) // Total length
        buffer.putShort(0) // Identification
        buffer.putShort(0) // Flags + Fragment offset
        buffer.put(64.toByte()) // TTL
        buffer.put(17.toByte()) // Protocol: UDP
        buffer.putShort(0) // Checksum (will calculate later)
        putIpAddress(buffer, srcIp)
        putIpAddress(buffer, destIp)

        // Calculate IP header checksum
        val ipChecksum = calculateChecksum(buffer.array(), 0, ipHeaderLength)
        buffer.putShort(10, ipChecksum.toShort())

        // Build UDP header
        buffer.putShort(srcPort.toShort())
        buffer.putShort(destPort.toShort())
        buffer.putShort((udpHeaderLength + payload.size).toShort()) // UDP length
        buffer.putShort(0) // Checksum (will calculate later)

        // Add payload
        buffer.put(payload)

        // Calculate UDP checksum (with pseudo-header)
        val udpChecksum = calculateUdpChecksum(
            buffer.array(),
            ipHeaderLength,
            srcIp,
            destIp,
            udpHeaderLength + payload.size
        )
        buffer.putShort(ipHeaderLength + 6, udpChecksum.toShort())

        return buffer.array()
    }

    /**
     * Write IPv4 address to buffer (4 bytes).
     */
    private fun putIpAddress(buffer: ByteBuffer, ip: String) {
        val parts = ip.split(".")
        require(parts.size == 4) { "Invalid IP address: $ip" }
        parts.forEach { buffer.put(it.toInt().toByte()) }
    }

    /**
     * Calculate IP header checksum.
     */
    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L

        var i = offset
        while (i < offset + length) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }

        // Add carry
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv().toInt() and 0xFFFF)
    }

    /**
     * Calculate UDP checksum (includes pseudo-header).
     */
    private fun calculateUdpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        srcIp: String,
        destIp: String,
        udpLength: Int
    ): Int {
        var sum = 0L

        // Pseudo-header
        val srcParts = srcIp.split(".")
        val destParts = destIp.split(".")

        // Source IP
        sum += ((srcParts[0].toInt() shl 8) or srcParts[1].toInt())
        sum += ((srcParts[2].toInt() shl 8) or srcParts[3].toInt())

        // Dest IP
        sum += ((destParts[0].toInt() shl 8) or destParts[1].toInt())
        sum += ((destParts[2].toInt() shl 8) or destParts[3].toInt())

        // Protocol (17 for UDP)
        sum += 17

        // UDP length
        sum += udpLength

        // UDP header + data
        var i = udpOffset
        var remaining = udpLength
        while (remaining > 1) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
            remaining -= 2
        }

        // Handle odd byte
        if (remaining == 1) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }

        // Add carry
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv().toInt() and 0xFFFF)
    }
}

