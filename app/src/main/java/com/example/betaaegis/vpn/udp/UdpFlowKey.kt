package com.example.betaaegis.vpn.udp

/**
 * Phase 3: UDP Flow Identifier
 *
 * Uniquely identifies a UDP "pseudo-flow" by 5-tuple.
 *
 * UDP is connectionless, but we treat it as a flow with:
 * - 5-tuple key (src IP/port, dest IP/port, protocol)
 * - Last activity timestamp
 * - Idle timeout
 *
 * Used as key in flow map to track individual UDP streams.
 */
data class UdpFlowKey(
    val srcIp: String,
    val srcPort: Int,
    val destIp: String,
    val destPort: Int
) {
    override fun toString(): String {
        return "$srcIp:$srcPort -> $destIp:$destPort (UDP)"
    }
}

