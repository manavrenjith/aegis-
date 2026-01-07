package com.example.betaaegis.vpn.tcp

import android.util.Log
import com.example.betaaegis.vpn.AegisVpnService
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 2: Single TCP Flow Connection
 *
 * Manages a single TCP connection through the VPN:
 * - Creates protected socket to destination
 * - Forwards data bidirectionally as streams
 * - Handles lifecycle (connect → forward → close)
 *
 * OWNERSHIP:
 * Once created, this object owns the TCP connection.
 * The kernel no longer manages this flow.
 * If this object does nothing, the connection dies.
 */
class TcpConnection(
    private val key: TcpFlowKey,
    private val vpnService: AegisVpnService,
    private val tunOutputStream: FileOutputStream
) {
    private var socket: Socket? = null
    @Volatile private var state = TcpFlowState.NEW
    @Volatile private var isActive = false
    private var downlinkThread: Thread? = null

    // Simplified sequence tracking (Phase 2: minimal viable)
    private var nextSeqNum = 1000L
    private var nextAckNum = 1L

    companion object {
        private const val TAG = "TcpConnection"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_BUFFER_SIZE = 8192
        private const val TCP_PSH_ACK = 0x18
        private const val TCP_FIN_ACK = 0x11
    }

    /**
     * Connect to destination server.
     *
     * CRITICAL: Socket must be protected before connecting.
     *
     * Why protection is mandatory:
     * - Without protect(), socket routes back into VPN
     * - Creates infinite loop: TUN → VPN → TUN → VPN → ...
     * - App never reaches internet
     * - May cause stack overflow or deadlock
     *
     * Failure mode without protection:
     * App → TUN → VpnService → Socket → TUN → VpnService → Socket → ...
     */
    fun connect() {
        state = TcpFlowState.CONNECTING

        val sock = vpnService.createProtectedTcpSocket()

        try {
            sock.connect(
                InetSocketAddress(key.destIp, key.destPort),
                CONNECT_TIMEOUT_MS
            )

            socket = sock
            state = TcpFlowState.ESTABLISHED
            isActive = true

            Log.d(TAG, "Connected: $key")

        } catch (e: IOException) {
            sock.close()
            throw IOException("Failed to connect to ${key.destIp}:${key.destPort}", e)
        }
    }

    /**
     * Send SYN-ACK response to app.
     *
     * This completes the TCP handshake from the app's perspective.
     */
    fun sendSynAck() {
        try {
            val packet = TcpPacketBuilder.build(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                flags = 0x12, // SYN + ACK
                seqNum = nextSeqNum++,
                ackNum = nextAckNum,
                payload = byteArrayOf()
            )

            synchronized(tunOutputStream) {
                tunOutputStream.write(packet)
            }

        } catch (e: IOException) {
            Log.e(TAG, "Failed to send SYN-ACK for $key", e)
        }
    }

    /**
     * Start bidirectional stream forwarding.
     *
     * Launches a background thread to read from server and write to app.
     * Uplink (app → server) is handled by sendToServer() calls.
     *
     * @param bytesDownlink Atomic counter for downlink bytes (telemetry)
     */
    fun startForwarding(bytesDownlink: AtomicLong) {
        val sock = socket ?: return

        // Start downlink thread (server → app)
        downlinkThread = Thread {
            try {
                val buffer = ByteArray(READ_BUFFER_SIZE)
                val inputStream = sock.getInputStream()

                while (isActive && !Thread.currentThread().isInterrupted) {
                    val bytesRead = inputStream.read(buffer)

                    if (bytesRead == -1) {
                        // Server closed connection
                        Log.d(TAG, "Server closed connection: $key")
                        break
                    }

                    if (bytesRead > 0) {
                        // Construct TCP response packet
                        val packet = TcpPacketBuilder.build(
                            srcIp = key.destIp,
                            srcPort = key.destPort,
                            destIp = key.srcIp,
                            destPort = key.srcPort,
                            flags = TCP_PSH_ACK,
                            seqNum = nextSeqNum,
                            ackNum = nextAckNum,
                            payload = buffer.copyOf(bytesRead)
                        )

                        nextSeqNum += bytesRead

                        // Write to TUN (synchronized to prevent corruption)
                        synchronized(tunOutputStream) {
                            tunOutputStream.write(packet)
                        }

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
            name = "TCP-Downlink-${key.destPort}"
            start()
        }
    }

    /**
     * Send data from app to server (uplink).
     *
     * Called when app sends data through VPN.
     */
    fun sendToServer(payload: ByteArray) {
        if (!isActive || payload.isEmpty()) return

        try {
            socket?.getOutputStream()?.write(payload)
            socket?.getOutputStream()?.flush()
            nextAckNum += payload.size
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send to server for $key", e)
            close()
        }
    }

    /**
     * Handle ACK from app (updates our ack tracking).
     */
    fun handleAck(ackNum: Long) {
        // Phase 2: Simplified - just update our tracking
        // Full TCP would validate sequence numbers here
    }

    /**
     * Gracefully close connection.
     *
     * Called when FIN received from app.
     */
    fun closeGracefully() {
        if (state == TcpFlowState.CLOSED) return

        state = TcpFlowState.CLOSING

        try {
            // Send FIN-ACK to app
            val packet = TcpPacketBuilder.build(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                flags = TCP_FIN_ACK,
                seqNum = nextSeqNum,
                ackNum = nextAckNum,
                payload = byteArrayOf()
            )

            synchronized(tunOutputStream) {
                tunOutputStream.write(packet)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send FIN-ACK for $key", e)
        }

        close()
    }

    /**
     * Immediate close (cleanup resources).
     *
     * Cleanup order:
     * 1. Mark as inactive
     * 2. Update state
     * 3. Close socket
     * 4. Interrupt downlink thread
     */
    fun close() {
        if (state == TcpFlowState.CLOSED) return

        isActive = false
        state = TcpFlowState.CLOSED

        try {
            socket?.close()
        } catch (e: IOException) {
            // Ignore close errors
        }

        downlinkThread?.interrupt()

        Log.v(TAG, "Closed: $key")
    }

    /**
     * Send RST to app (connection rejected or error).
     */
    fun sendRst() {
        try {
            val packet = TcpPacketBuilder.build(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                flags = 0x04, // RST
                seqNum = nextSeqNum,
                ackNum = nextAckNum,
                payload = byteArrayOf()
            )

            synchronized(tunOutputStream) {
                tunOutputStream.write(packet)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send RST for $key", e)
        }
    }

    fun getState(): TcpFlowState = state
}

