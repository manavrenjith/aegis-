package com.example.betaaegis.vpn.tcp

import android.util.Log
import com.example.betaaegis.vpn.AegisVpnService
import java.io.FileOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * NetGuard-grade TCP Flow Connection
 *
 * Manages a single TCP connection through the VPN with proper state machine:
 * - Creates protected socket to destination
 * - Forwards data bidirectionally as streams
 * - Handles lifecycle with correct TCP semantics
 * - NEVER sends RST in ESTABLISHED unless provably invalid
 *
 * OWNERSHIP:
 * Once created, this object owns the TCP connection.
 * The kernel no longer manages this flow.
 *
 * NOTE (Phase 0):
 * This class implements packet-based TCP flow management.
 * It is intentionally preserved for debugging, comparison, and rollback purposes
 * while a TCP proxy is developed in parallel.
 *
 * DO NOT refactor or extend this class.
 * This implementation is frozen and will be replaced by VirtualTcpConnection in future phases.
 */
class TcpConnection(
    private val key: TcpFlowKey,
    private val vpnService: AegisVpnService,
    private val tunOutputStream: FileOutputStream
) {
    private var socket: Socket? = null
    @Volatile private var state = TcpFlowState.CLOSED
    @Volatile private var isActive = false
    private var downlinkThread: Thread? = null

    // Sequence tracking
    private var serverSeq = 0L
    private var appSeq = 0L
    private var nextSeqToApp = 1000L
    private var nextAckToServer = 1L

    companion object {
        private const val TAG = "TcpConnection"
        private const val READ_BUFFER_SIZE = 8192
        private const val TCP_ACK = 0x10
        private const val TCP_PSH_ACK = 0x18
        private const val TCP_FIN_ACK = 0x11
        private const val TCP_RST = 0x04
        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    /**
     * Connect to destination server.
     * State: CLOSED -> SYN_SENT
     */
    fun connect() {
        state = TcpFlowState.SYN_SENT

        try {
            val sock = vpnService.createAndConnectProtectedTcpSocket(
                java.net.InetAddress.getByName(key.destIp),
                key.destPort,
                CONNECT_TIMEOUT_MS
            )

            socket = sock
            Log.d(TAG, "Connected: $key")

        } catch (e: Exception) {
            state = TcpFlowState.RESET
            throw IOException("Failed to connect to ${key.destIp}:${key.destPort}", e)
        }
    }

    /**
     * Send SYN-ACK response to app and transition to ESTABLISHED.
     * State: SYN_SENT -> ESTABLISHED
     */
    fun sendSynAck() {
        if (state != TcpFlowState.SYN_SENT) {
            Log.w(TAG, "sendSynAck called in wrong state: $state for $key")
            return
        }

        try {
            val mssOption = byteArrayOf(
                2,
                4,
                (1360 shr 8).toByte(),
                (1360 and 0xFF).toByte()
            )

            val packet = TcpPacketBuilder.build(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                flags = 0x12, // SYN + ACK
                seqNum = nextSeqToApp++,
                ackNum = nextAckToServer,
                payload = byteArrayOf(),
                tcpOptions = mssOption
            )

            synchronized(tunOutputStream) {
                tunOutputStream.write(packet)
            }

            // Transition to ESTABLISHED
            state = TcpFlowState.ESTABLISHED
            Log.d(TAG, "State: SYN_SENT -> ESTABLISHED for $key")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to send SYN-ACK for $key", e)
            state = TcpFlowState.RESET
        }
    }

    /**
     * Start bidirectional stream forwarding.
     * Must be called in ESTABLISHED state.
     *
     * Downlink (server → app):
     * - Blocking read loop on socket
     * - Forwards ALL data to app via TUN
     * - Accepts ANY data size
     * - NEVER sends RST for unexpected data
     * - NEVER validates TLS/HTTP content
     */
    fun startForwarding(bytesDownlink: AtomicLong) {
        if (state != TcpFlowState.ESTABLISHED) {
            Log.w(TAG, "startForwarding called in wrong state: $state for $key")
            return
        }

        val sock = socket ?: return
        isActive = true

        // Start downlink thread (server → app)
        downlinkThread = Thread {
            try {
                val inputStream = sock.getInputStream()

                while (isActive && !Thread.currentThread().isInterrupted) {
                    val buffer = ByteArray(READ_BUFFER_SIZE)
                    val bytesRead = inputStream.read(buffer)

                    if (bytesRead == -1) {
                        // Server closed connection gracefully
                        Log.d(TAG, "Server closed connection: $key")
                        handleServerFin()
                        break
                    }

                    if (bytesRead > 0) {
                        val payload = buffer.copyOf(bytesRead)

                        // NetGuard-grade behavior:
                        // Accept ALL data from server in ESTABLISHED
                        // This includes:
                        // - TLS ServerHello, certificates, encrypted data
                        // - HTTP headers and body
                        // - Any application protocol data
                        //
                        // NEVER reject or RST based on:
                        // - Payload size
                        // - Content inspection
                        // - Sequence number mismatches
                        val packet = TcpPacketBuilder.build(
                            srcIp = key.destIp,
                            srcPort = key.destPort,
                            destIp = key.srcIp,
                            destPort = key.srcPort,
                            flags = TCP_PSH_ACK,
                            seqNum = nextSeqToApp,
                            ackNum = nextAckToServer,
                            payload = payload
                        )

                        synchronized(tunOutputStream) {
                            tunOutputStream.write(packet)
                            tunOutputStream.flush()
                        }

                        nextSeqToApp += bytesRead
                        bytesDownlink.addAndGet(bytesRead.toLong())
                    }
                }

            } catch (e: IOException) {
                if (isActive) {
                    Log.d(TAG, "Downlink error for $key: ${e.message}")
                }
            } finally {
                close()
            }
        }.apply {
            name = "TCP-Downlink-${key.srcPort}->${key.destPort}"
            start()
        }
    }

    /**
     * Handle data from app in ESTABLISHED state.
     *
     * NetGuard-grade FAIL-OPEN behavior:
     * - Accept ALL ACK packets
     * - Accept ALL payload (TLS, HTTP, etc.)
     * - NEVER send RST
     * - NEVER validate sequence numbers strictly
     * - NEVER reject unexpected data
     *
     * This handles:
     * - TLS handshake (ServerHello, certificates)
     * - Application data
     * - Reordered packets
     * - Duplicate ACKs
     * - Out-of-window packets
     */
    fun handleEstablishedPacket(metadata: TcpMetadata) {
        // Defensive check: only process in ESTABLISHED
        if (state != TcpFlowState.ESTABLISHED) {
            // Wrong state - silently ignore
            // NEVER send RST
            return
        }

        // Accept ALL ACKs without strict validation
        // The app and server will negotiate correctness naturally
        if (metadata.isAck) {
            // Update tracking if needed (advisory only)
            // DO NOT enforce strict sequence/ack matching
        }

        // Forward ANY payload to server
        // This includes:
        // - TLS ClientHello continuation
        // - HTTP requests
        // - Application data
        // - Retransmissions
        if (metadata.payload.isNotEmpty()) {
            sendToServer(metadata.payload)
        }

        // CRITICAL: NEVER send RST in ESTABLISHED
        // Let the endpoints handle protocol errors naturally
    }

    /**
     * Send data from app to server (uplink).
     *
     * NetGuard-grade behavior:
     * - Accept ANY payload size
     * - Handle socket errors gracefully
     * - NEVER send RST to app on write failure
     * - Close connection cleanly on errors
     */
    fun sendToServer(payload: ByteArray) {
        if (!isActive || payload.isEmpty()) return

        try {
            val outputStream = socket?.getOutputStream()
            if (outputStream == null) {
                // Socket closed - clean shutdown
                close()
                return
            }

            // Write complete payload to server
            // Block until written (TCP guarantees delivery)
            outputStream.write(payload)
            outputStream.flush()

            // Update tracking
            nextAckToServer += payload.size

        } catch (e: IOException) {
            // Socket write failed - server likely closed
            // Clean shutdown, no RST needed
            Log.d(TAG, "Socket write failed for $key: ${e.message}")
            close()
        }
    }

    /**
     * Handle FIN from server.
     * State: ESTABLISHED -> FIN_WAIT_APP
     */
    private fun handleServerFin() {
        if (state != TcpFlowState.ESTABLISHED) {
            return
        }

        Log.d(TAG, "Server FIN: $key, state: $state -> FIN_WAIT_APP")
        state = TcpFlowState.FIN_WAIT_APP

        try {
            // Send FIN-ACK to app
            val packet = TcpPacketBuilder.build(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                flags = TCP_FIN_ACK,
                seqNum = nextSeqToApp,
                ackNum = nextAckToServer,
                payload = byteArrayOf()
            )

            synchronized(tunOutputStream) {
                tunOutputStream.write(packet)
            }

            nextSeqToApp++

        } catch (e: IOException) {
            Log.w(TAG, "Failed to send FIN-ACK for $key", e)
        }
    }

    /**
     * Handle FIN from app.
     * State: ESTABLISHED -> FIN_WAIT_SERVER
     */
    fun handleAppFin() {
        when (state) {
            TcpFlowState.ESTABLISHED -> {
                Log.d(TAG, "App FIN: $key, state: $state -> FIN_WAIT_SERVER")
                state = TcpFlowState.FIN_WAIT_SERVER

                try {
                    // Close socket write side
                    socket?.shutdownOutput()
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to shutdown output for $key", e)
                }
            }
            TcpFlowState.FIN_WAIT_APP -> {
                // Both sides closed
                Log.d(TAG, "App FIN in FIN_WAIT_APP: $key, -> TIME_WAIT")
                state = TcpFlowState.TIME_WAIT
                close()
            }
            else -> {
                // Ignore FIN in other states
            }
        }
    }

    /**
     * Handle RST from app or server.
     * State: Any -> RESET -> CLOSED
     */
    fun handleRst() {
        Log.d(TAG, "RST received: $key, state: $state -> RESET")
        state = TcpFlowState.RESET
        close()
    }
    /**
     * Immediate close (cleanup resources).
     * Can be called from any state.
     */
    fun close() {
        if (state == TcpFlowState.CLOSED) return

        val oldState = state
        isActive = false
        state = TcpFlowState.CLOSED

        try {
            socket?.close()
        } catch (e: IOException) {
            // Ignore close errors
        }

        downlinkThread?.interrupt()

        Log.v(TAG, "Closed: $key (was $oldState)")
    }

    fun getState(): TcpFlowState = state
}

