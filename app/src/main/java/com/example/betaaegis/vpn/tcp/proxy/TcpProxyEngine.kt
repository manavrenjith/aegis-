package com.example.betaaegis.vpn.tcp.proxy

import android.util.Log
import com.example.betaaegis.vpn.AegisVpnService
import com.example.betaaegis.vpn.tcp.TcpFlowKey
import com.example.betaaegis.vpn.tcp.TcpMetadata
import com.example.betaaegis.vpn.tcp.TcpPacketBuilder
import java.io.FileOutputStream
import java.net.InetAddress

/**
 * Phase 4: TCP Proxy Engine (Complete Lifecycle)
 *
 * User-space TCP proxy that terminates TCP connections inside the VPN.
 * Phase 4 adds complete connection lifecycle with FIN/RST handling.
 *
 * Architecture:
 * - Intercepts TCP SYN packets
 * - Sends SYN-ACK back to app via TUN
 * - Accepts ACK to complete handshake
 * - Creates outbound socket to destination
 * - Forwards app data → outbound socket (uplink)
 * - Forwards socket data → app via TUN (downlink)
 * - Handles graceful shutdown (FIN from app or server)
 * - Handles error cases (RST)
 * - Cleans up resources and evicts flows
 *
 * Phase 4 Status: Complete connection lifecycle
 */
class TcpProxyEngine(
    private val vpnService: AegisVpnService,
    private val tunOutputStream: FileOutputStream
) {
    private val connections = mutableMapOf<TcpFlowKey, VirtualTcpConnection>()

    companion object {
        private const val TAG = "TcpProxyEngine"

        // TCP flags
        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10

        // TCP window size (static, safe value)
        private const val TCP_WINDOW = 65535
    }

    /**
     * Phase 4: Handle incoming TCP packet (complete lifecycle)
     *
     * On SYN: Send SYN-ACK back to app
     * On ACK: Create outbound socket + start downlink reader
     * On ESTABLISHED + data: Forward to outbound socket (uplink)
     * On FIN: Handle graceful shutdown
     * On RST: Handle connection abort
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

        // Phase 4: Complete lifecycle handling
        when {
            metadata.isRst -> {
                // RST from app - abort connection
                conn.onRstReceived()
                Log.d(TAG, "RST received from app: $key")
                evictFlow(key)
            }

            metadata.isSyn && !metadata.isAck -> {
                // Phase 2: Handle SYN - send SYN-ACK
                handleSyn(conn, metadata)
            }

            metadata.isFin -> {
                // Phase 4: Handle FIN from app
                handleFin(conn, metadata)
            }

            metadata.isAck -> {
                // Phase 2/3: Handle ACK or data
                handleAck(conn, metadata)
            }
        }
    }

    // ...existing code...
    private fun handleSyn(conn: VirtualTcpConnection, metadata: TcpMetadata) {
        conn.onSynReceived(metadata.seqNum)

        Log.d(TAG, "SYN received → sending SYN-ACK: ${conn.key}")

        val synAckPacket = TcpPacketBuilder.build(
            srcIp = conn.key.destIp,
            srcPort = conn.key.destPort,
            destIp = conn.key.srcIp,
            destPort = conn.key.srcPort,
            flags = TCP_SYN or TCP_ACK,
            seqNum = conn.serverSeq,
            ackNum = conn.serverAck,
            payload = byteArrayOf()
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(synAckPacket)
        }

        Log.d(TAG, "SYN-ACK sent: ${conn.key} (seq=${conn.serverSeq}, ack=${conn.serverAck})")
    }

    /**
     * Phase 4: Handle FIN from app
     */
    private fun handleFin(conn: VirtualTcpConnection, metadata: TcpMetadata) {
        when (conn.state) {
            VirtualTcpState.ESTABLISHED -> {
                // Graceful shutdown initiated by app
                conn.handleAppFin(tunOutputStream)
                // Don't evict yet - waiting for server to close
            }

            VirtualTcpState.FIN_WAIT_APP -> {
                // App acknowledged server's FIN - both sides closed
                Log.d(TAG, "FIN from app (both closed) → cleanup: ${conn.key}")
                evictFlow(conn.key)
            }

            else -> {
                // Unexpected FIN, ignore
            }
        }
    }

    // ...existing code...
    private fun handleAck(conn: VirtualTcpConnection, metadata: TcpMetadata) {
        when (conn.state) {
            VirtualTcpState.SYN_SEEN -> {
                if (conn.onAckReceived(metadata.ackNum)) {
                    Log.d(TAG, "ACK received → ESTABLISHED: ${conn.key}")
                    createOutboundSocket(conn)
                    conn.startDownlinkReader(tunOutputStream)
                }
            }

            VirtualTcpState.ESTABLISHED -> {
                if (metadata.payload.isNotEmpty()) {
                    forwardUplink(conn, metadata.payload)
                } else {
                    // ACK-only packet from app
                    Log.d(
                        "TcpProxy",
                        "APP ACK:\n  ackNum=${metadata.ackNum}"
                    )
                }
            }

            VirtualTcpState.FIN_WAIT_SERVER -> {
                // App still sending data after FIN (ignore)
            }

            else -> {
                // Unexpected state, ignore
            }
        }
    }

    // ...existing code...
    private fun forwardUplink(conn: VirtualTcpConnection, payload: ByteArray) {
        try {
            conn.outboundSocket?.getOutputStream()?.write(payload)
            conn.onDataReceived(payload.size)
            Log.d(TAG, "Forwarded uplink payload size=${payload.size} flow=${conn.key}")
        } catch (e: Exception) {
            Log.e(TAG, "Uplink forwarding error: ${conn.key} - ${e.message}")
            conn.handleRst(tunOutputStream)
            evictFlow(conn.key)
        }
    }

    // ...existing code...
    private fun createOutboundSocket(conn: VirtualTcpConnection) {
        try {
            val destIp = InetAddress.getByName(conn.key.destIp)
            val destPort = conn.key.destPort

            val socket = vpnService.openProxySocket(destIp, destPort)
            conn.outboundSocket = socket

            Log.d(TAG, "Outbound socket created: ${conn.key}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create outbound socket: ${conn.key} - ${e.message}")
            conn.handleRst(tunOutputStream)
            evictFlow(conn.key)
        }
    }

    /**
     * Phase 4: Evict flow from connection map
     * Performs cleanup and removes connection
     * Idempotent - safe to call multiple times
     */
    private fun evictFlow(key: TcpFlowKey) {
        val conn = connections.remove(key) ?: return
        conn.close()
    }

    /**
     * Clean up all virtual connections
     * Phase 4: Complete cleanup with eviction
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down TCP proxy engine, ${connections.size} connections tracked")
        val keys = connections.keys.toList()
        keys.forEach { evictFlow(it) }
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

