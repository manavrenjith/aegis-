package com.example.betaaegis.vpn.tcp

/**
 * Phase 2: TCP Flow Identifier
 *
 * Uniquely identifies a TCP connection by 4-tuple.
 * Used as key in flow map to track individual connections.
 */
data class TcpFlowKey(
    val srcIp: String,
    val srcPort: Int,
    val destIp: String,
    val destPort: Int
) {
    override fun toString(): String {
        return "$srcIp:$srcPort -> $destIp:$destPort"
    }
}

