package com.example.betaaegis.vpn.tcp.proxy

import android.util.Log
import com.example.betaaegis.vpn.AegisVpnService
import com.example.betaaegis.vpn.tcp.TcpFlowKey
import com.example.betaaegis.vpn.tcp.TcpMetadata

/**
 * Phase 1: TCP Proxy Engine (Passive Observer)
 *
 * User-space TCP proxy that terminates TCP connections inside the VPN.
 * Phase 1 is observation-only: tracks flows but does not forward traffic.
 *
 * Architecture:
 * - Intercepts TCP SYN packets
 * - Creates VirtualTcpConnection instances
 * - Tracks state transitions
 * - Does NOT send packets back to apps yet
 * - Does NOT create sockets yet
 * - Does NOT forward payload yet
 *
 * Future phases will add:
 * - TCP handshake emulation (SYN-ACK generation)
 * - Socket creation and connection
 * - Bidirectional stream forwarding
 * - Proper TCP state machine implementation
 *
 * Phase 1 Status: Passive observation only
 */
class TcpProxyEngine(
    private val vpnService: AegisVpnService
) {
    private val connections = mutableMapOf<TcpFlowKey, VirtualTcpConnection>()

    companion object {
        private const val TAG = "TcpProxyEngine"
    }

    /**
     * Phase 1: Handle incoming TCP packet (observe only)
     *
     * Creates virtual connection on SYN
     * Tracks state transitions
     * Does NOT forward traffic
     * Does NOT send responses
     */
    fun handlePacket(metadata: TcpMetadata) {
        val key = TcpFlowKey(
            metadata.srcIp,
            metadata.srcPort,
            metadata.destIp,
            metadata.destPort
        )

        // Get or create virtual connection
        val conn = connections.getOrPut(key) {
            VirtualTcpConnection(key)
        }

        // Phase 1: Observe state transitions only
        when {
            metadata.isRst -> {
                conn.onRstReceived()
                Log.d(TAG, "RST observed: $key")
                // Phase 2+: Will trigger cleanup
            }

            metadata.isSyn && !metadata.isAck -> {
                conn.onSynReceived()
                Log.d(TAG, "SYN observed: $key -> state=${conn.state}")
                // Phase 2+: Will trigger handshake emulation
            }

            metadata.isFin -> {
                conn.onFinReceived()
                Log.d(TAG, "FIN observed: $key")
                // Phase 2+: Will trigger graceful shutdown
            }

            metadata.isAck -> {
                Log.v(TAG, "ACK/data observed: $key state=${conn.state} payload=${metadata.payload.size}")
                // Phase 2+: Will forward to socket
            }
        }

        // Phase 1: No packet forwarding, just observation
    }

    /**
     * Clean up all virtual connections
     * Phase 1: No resources to release yet
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down TCP proxy engine, ${connections.size} connections tracked")
        connections.values.forEach { it.close() }
        connections.clear()
    }

    /**
     * Get active connection count for telemetry
     */
    fun getActiveConnectionCount(): Int = connections.size

    /**
     * Get connection state for debugging
     */
    fun getConnectionState(key: TcpFlowKey): VirtualTcpState? {
        return connections[key]?.state
    }
}

