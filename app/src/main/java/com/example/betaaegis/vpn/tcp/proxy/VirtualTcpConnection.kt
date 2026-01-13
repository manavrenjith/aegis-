package com.example.betaaegis.vpn.tcp.proxy

import com.example.betaaegis.vpn.tcp.TcpFlowKey
import java.net.Socket

/**
 * Phase 1: Virtual TCP Connection (Passive Observer)
 *
 * Represents a single user-space TCP proxy connection.
 * In Phase 1, this only observes packets and tracks state transitions.
 *
 * Future responsibilities (Phase 2+):
 * - Manage TCP state machine for virtual connection (app side)
 * - Send SYN-ACK, ACK, data, and FIN packets to app via TUN
 * - Maintain sequence/acknowledgment numbers
 * - Forward data between app and real socket
 * - Handle graceful shutdown and errors
 *
 * Phase 1: Observation only, no forwarding
 */
class VirtualTcpConnection(
    val key: TcpFlowKey
) {
    /**
     * Current state of this virtual connection
     */
    var state: VirtualTcpState = VirtualTcpState.CLOSED
        private set

    /**
     * Outbound socket to destination server
     * Phase 1: Reserved, not used yet
     */
    var outboundSocket: Socket? = null
        private set

    /**
     * Phase 1: Observe state transition
     * Phase 2+: Will trigger handshake emulation
     */
    fun onSynReceived() {
        if (state == VirtualTcpState.CLOSED) {
            state = VirtualTcpState.SYN_SEEN
        }
    }

    /**
     * Phase 1: Observe RST
     * Phase 2+: Will trigger cleanup
     */
    fun onRstReceived() {
        state = VirtualTcpState.RESET
    }

    /**
     * Phase 1: Observe FIN
     * Phase 2+: Will trigger graceful shutdown
     */
    fun onFinReceived() {
        // Reserved for Phase 2+
    }

    /**
     * Phase 1: No cleanup needed (no resources allocated)
     * Phase 2+: Will close socket and release resources
     */
    fun close() {
        outboundSocket?.close()
        outboundSocket = null
        state = VirtualTcpState.CLOSED
    }

    override fun toString(): String {
        return "VirtualTcpConnection(key=$key, state=$state)"
    }
}

