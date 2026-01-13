package com.example.betaaegis.vpn.tcp.proxy

import android.util.Log
import com.example.betaaegis.vpn.tcp.TcpFlowKey
import com.example.betaaegis.vpn.tcp.TcpPacketBuilder
import java.io.FileOutputStream
import java.net.Socket
import kotlin.random.Random

/**
 * Phase 4: Virtual TCP Connection (Complete Lifecycle)
 *
 * Represents a single user-space TCP proxy connection.
 * Phase 4 adds complete connection lifecycle with FIN/RST handling.
 *
 * Responsibilities:
 * - Manage TCP state machine for virtual connection (app side)
 * - Track sequence/acknowledgment numbers
 * - Forward data bidirectionally
 * - Handle graceful shutdown (FIN from app or server)
 * - Handle error cases (RST)
 * - Clean up resources properly
 *
 * Phase 4: Complete connection lifecycle
 */
class VirtualTcpConnection(
    val key: TcpFlowKey
) {
    /**
     * Current state of this virtual connection
     */
    var state: VirtualTcpState = VirtualTcpState.CLOSED
        private set

    // ...existing code...
    var clientSeq: Long = 0
        private set
    var clientAck: Long = 0
        private set
    var serverSeq: Long = generateInitialSeq()
        private set
    var serverAck: Long = 0
        private set
    var clientDataBytesSeen: Long = 0
        private set
    var serverDataBytesSent: Long = 0
        private set
    var outboundSocket: Socket? = null
        internal set
    private var downlinkThread: Thread? = null

    @Volatile
    private var isReaderActive = false

    @Volatile
    private var closed = false

    companion object {
        private const val TAG = "VirtualTcpConn"
        private const val READ_BUFFER_SIZE = 16 * 1024

        // TCP flags
        private const val TCP_FIN = 0x01
        private const val TCP_ACK = 0x10
        private const val TCP_PSH = 0x08
        private const val TCP_RST = 0x04

        private fun generateInitialSeq(): Long {
            return Random.nextLong(100_000L, 1_000_000L)
        }
    }

    // ...existing code...
    fun onSynReceived(seqNum: Long) {
        if (state == VirtualTcpState.CLOSED) {
            clientSeq = seqNum
            serverAck = seqNum + 1
            state = VirtualTcpState.SYN_SEEN
        }
    }

    fun onAckReceived(ackNum: Long): Boolean {
        if (state == VirtualTcpState.SYN_SEEN) {
            if (ackNum == serverSeq + 1) {
                state = VirtualTcpState.ESTABLISHED
                return true
            }
        }
        return false
    }

    fun onDataReceived(payloadSize: Int) {
        clientDataBytesSeen += payloadSize
    }

    /**
     * Phase 4: Handle FIN from app (graceful shutdown)
     * Performs half-close on outbound socket
     */
    fun handleAppFin(tunOutputStream: FileOutputStream) {
        if (state == VirtualTcpState.ESTABLISHED) {
            Log.d(TAG, "FIN from app → half-close: $key")

            state = VirtualTcpState.FIN_WAIT_SERVER

            // Half-close: shutdown output but keep reading
            try {
                outboundSocket?.shutdownOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to shutdown output: $key - ${e.message}")
            }
        }
    }

    /**
     * Phase 4: Handle RST (error or abort)
     * Sends RST to app and transitions to RESET
     */
    fun handleRst(tunOutputStream: FileOutputStream) {
        if (closed) return

        Log.d(TAG, "RST sent → socket error: $key")

        sendRstToApp(tunOutputStream)
        state = VirtualTcpState.RESET
        stopDownlinkReader()
    }

    /**
     * Phase 3: Observe RST from app
     */
    fun onRstReceived() {
        state = VirtualTcpState.RESET
        stopDownlinkReader()
    }

    /**
     * Phase 4: Start downlink reader thread
     * Handles EOF and FIN properly
     */
    fun startDownlinkReader(tunOutputStream: FileOutputStream) {
        if (isReaderActive || outboundSocket == null || closed) {
            return
        }

        isReaderActive = true

        downlinkThread = Thread {
            try {
                val inputStream = outboundSocket!!.getInputStream()
                val buffer = ByteArray(READ_BUFFER_SIZE)

                while (isReaderActive && !Thread.currentThread().isInterrupted && !closed) {
                    val bytesRead = inputStream.read(buffer)

                    if (bytesRead == -1) {
                        // EOF - server closed connection
                        handleServerFin(tunOutputStream)
                        break
                    }

                    if (bytesRead > 0) {
                        val payload = buffer.copyOf(bytesRead)
                        sendDataToApp(payload, tunOutputStream)
                        Log.d(TAG, "Forwarded downlink payload size=$bytesRead flow=$key")
                    }
                }
            } catch (e: Exception) {
                if (isReaderActive && !closed) {
                    Log.e(TAG, "Downlink reader error: $key - ${e.message}")
                    handleRst(tunOutputStream)
                }
            } finally {
                isReaderActive = false
            }
        }.apply {
            name = "TcpProxy-Downlink-$key"
            isDaemon = true
            start()
        }
    }

    /**
     * Phase 4: Handle EOF from server (server closed)
     * Sends FIN to app and transitions state
     */
    private fun handleServerFin(tunOutputStream: FileOutputStream) {
        if (closed) return

        Log.d(TAG, "FIN from server → closing: $key")

        when (state) {
            VirtualTcpState.ESTABLISHED -> {
                // Server closed first
                state = VirtualTcpState.FIN_WAIT_APP
                sendFinToApp(tunOutputStream)
            }
            VirtualTcpState.FIN_WAIT_SERVER -> {
                // Both sides closed - complete shutdown
                state = VirtualTcpState.CLOSED
                sendFinToApp(tunOutputStream)
            }
            else -> {
                // Unexpected state
            }
        }
    }

    /**
     * Phase 3: Send data to app via TUN
     */
    private fun sendDataToApp(payload: ByteArray, tunOutputStream: FileOutputStream) {
        if (closed) return

        val packet = TcpPacketBuilder.build(
            srcIp = key.destIp,
            srcPort = key.destPort,
            destIp = key.srcIp,
            destPort = key.srcPort,
            flags = TCP_ACK or TCP_PSH,
            seqNum = serverSeq + 1 + serverDataBytesSent,
            ackNum = serverAck + clientDataBytesSeen,
            payload = payload
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }

        serverDataBytesSent += payload.size
    }

    /**
     * Phase 4: Send FIN to app
     */
    private fun sendFinToApp(tunOutputStream: FileOutputStream) {
        if (closed) return

        val packet = TcpPacketBuilder.build(
            srcIp = key.destIp,
            srcPort = key.destPort,
            destIp = key.srcIp,
            destPort = key.srcPort,
            flags = TCP_FIN or TCP_ACK,
            seqNum = serverSeq + 1 + serverDataBytesSent,
            ackNum = serverAck + clientDataBytesSeen,
            payload = byteArrayOf()
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }

        serverDataBytesSent += 1  // FIN consumes sequence number
    }

    /**
     * Phase 4: Send RST to app
     */
    private fun sendRstToApp(tunOutputStream: FileOutputStream) {
        if (closed) return

        val packet = TcpPacketBuilder.build(
            srcIp = key.destIp,
            srcPort = key.destPort,
            destIp = key.srcIp,
            destPort = key.srcPort,
            flags = TCP_RST or TCP_ACK,
            seqNum = serverSeq + 1 + serverDataBytesSent,
            ackNum = serverAck + clientDataBytesSeen,
            payload = byteArrayOf()
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }
    }

    /**
     * Phase 3: Stop downlink reader thread
     */
    private fun stopDownlinkReader() {
        isReaderActive = false
        downlinkThread?.interrupt()
    }

    /**
     * Phase 4: Clean up connection resources (idempotent)
     * Can be called multiple times safely
     */
    fun close() {
        if (closed) return
        closed = true

        stopDownlinkReader()

        try {
            outboundSocket?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }

        outboundSocket = null
        state = VirtualTcpState.CLOSED

        Log.d(TAG, "Connection closed → cleanup complete: $key")
    }

    override fun toString(): String {
        return "VirtualTcpConnection(key=$key, state=$state, socket=${outboundSocket != null})"
    }
}

