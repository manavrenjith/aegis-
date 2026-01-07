package com.example.betaaegis.vpn.udp

import android.net.VpnService
import android.util.Log
import com.example.betaaegis.vpn.policy.FlowDecision
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 3: UDP Connection Handler
 *
 * Handles a single UDP "pseudo-flow".
 *
 * UDP is connectionless, but we model it as a flow with:
 * - Protected DatagramSocket
 * - Bidirectional forwarding
 * - Idle timeout
 *
 * ENFORCEMENT:
 * - ALLOW: Create socket, forward traffic
 * - BLOCK: This object is never created
 */
class UdpConnection(
    private val key: UdpFlowKey,
    private val vpnService: VpnService,
    private val tunOutputStream: FileOutputStream,
    private val decision: FlowDecision
) {
    private var socket: DatagramSocket? = null
    private var receiverThread: Thread? = null
    private val isActive = AtomicBoolean(false)
    private val lastActivity = AtomicLong(System.currentTimeMillis())

    companion object {
        private const val TAG = "UdpConnection"
        private const val BUFFER_SIZE = 2048
        private const val SOCKET_TIMEOUT_MS = 5000 // 5 seconds
    }

    /**
     * Initialize the UDP socket.
     *
     * Creates a protected DatagramSocket to prevent routing loop.
     */
    fun initialize() {
        if (decision == FlowDecision.BLOCK) {
            Log.d(TAG, "Flow blocked by policy: $key")
            return
        }

        try {
            // Create datagram socket
            socket = DatagramSocket()

            // CRITICAL: Protect socket to prevent routing loop
            if (!vpnService.protect(socket!!)) {
                Log.e(TAG, "Failed to protect socket for $key")
                close()
                return
            }

            // Set timeout for receive operations
            socket!!.soTimeout = SOCKET_TIMEOUT_MS

            isActive.set(true)

            // Start receiver thread for downlink
            startReceiver()

            Log.d(TAG, "UDP connection initialized: $key")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UDP connection: $key", e)
            close()
        }
    }

    /**
     * Send data to remote server (app → server).
     */
    fun sendToServer(payload: ByteArray) {
        if (!isActive.get()) {
            return
        }

        try {
            val address = InetAddress.getByName(key.destIp)
            val packet = DatagramPacket(payload, payload.size, address, key.destPort)

            socket?.send(packet)
            lastActivity.set(System.currentTimeMillis())

            Log.v(TAG, "Sent ${payload.size} bytes to ${key.destIp}:${key.destPort}")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to send UDP packet: $key", e)
        }
    }

    /**
     * Start receiver thread for downlink (server → app).
     */
    private fun startReceiver() {
        receiverThread = Thread {
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                while (isActive.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        if (packet.length > 0) {
                            val payload = packet.data.copyOf(packet.length)
                            sendToApp(payload)
                            lastActivity.set(System.currentTimeMillis())
                        }

                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout is normal, check if we should continue
                        continue
                    } catch (e: Exception) {
                        if (isActive.get()) {
                            Log.w(TAG, "UDP receive error: $key", e)
                        }
                        break
                    }
                }
            } finally {
                Log.d(TAG, "UDP receiver stopped: $key")
            }

        }.apply {
            name = "UdpReceiver-${key.srcPort}"
            isDaemon = true
            start()
        }
    }

    /**
     * Send data to app (server → app).
     *
     * Construct UDP packet and write to TUN.
     */
    private fun sendToApp(payload: ByteArray) {
        try {
            // Build UDP packet (swap src/dest for return path)
            val packet = UdpPacketBuilder.buildPacket(
                srcIp = key.destIp,
                srcPort = key.destPort,
                destIp = key.srcIp,
                destPort = key.srcPort,
                payload = payload
            )

            // Write to TUN (synchronized to prevent corruption)
            synchronized(tunOutputStream) {
                tunOutputStream.write(packet)
            }

            Log.v(TAG, "Sent ${payload.size} bytes to app: ${key.srcIp}:${key.srcPort}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send UDP packet to app: $key", e)
        }
    }

    /**
     * Check if connection is idle and should be closed.
     */
    fun isIdle(timeoutMs: Long): Boolean {
        val idleTime = System.currentTimeMillis() - lastActivity.get()
        return idleTime > timeoutMs
    }

    /**
     * Get last activity timestamp.
     */
    fun getLastActivity(): Long {
        return lastActivity.get()
    }

    /**
     * Close the UDP connection.
     */
    fun close() {
        if (!isActive.getAndSet(false)) {
            return
        }

        try {
            receiverThread?.interrupt()
            socket?.close()
            Log.d(TAG, "UDP connection closed: $key")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP connection: $key", e)
        }
    }
}

