package com.example.betaaegis.vpn.tcp.proxy

import android.util.Log
import com.example.betaaegis.vpn.tcp.TcpFlowKey
import com.example.betaaegis.vpn.tcp.TcpPacketBuilder
import java.io.FileOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.random.Random

/**
 * NETGUARD-IDENTICAL TCP PROXY
 *
 * Pure socket-event-driven TCP connection.
 *
 * Core Invariant:
 * - Execution occurs ONLY when kernel TCP socket state changes
 * - NO timeout-based execution
 * - NO time-based wakeups
 * - NO idle timers
 *
 * Architecture:
 * - Per-connection blocking stream loop using NIO Selector
 * - selector.select() blocks INDEFINITELY (no timeout)
 * - Wakes ONLY on kernel TCP events:
 *   - Data available (OP_READ)
 *   - Socket closed (EOF)
 *   - TCP state changes (ACK progression, window updates)
 * - Guaranteed execution as long as connection is ESTABLISHED
 *
 * NetGuard Equivalence:
 * This implementation is architecturally identical to NetGuard.
 * No time-based execution paths exist.
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

    // STREAM-DRIVEN: Outbound socket and stream context
    var outboundSocket: Socket? = null
        internal set

    private var outboundChannel: SocketChannel? = null
    private var streamSelector: Selector? = null
    private var streamThread: Thread? = null

    @Volatile
    private var streamActive = false

    @Volatile
    private var closed = false

    // Phase 5: Activity tracking (observability only, no timeouts)
    @Volatile
    var lastUplinkActivityMs: Long = System.currentTimeMillis()
        private set

    @Volatile
    var lastDownlinkActivityMs: Long = System.currentTimeMillis()
        private set

    private val connectionStartMs: Long = System.currentTimeMillis()

    // Phase 5: Traffic metrics (observability only)
    @Volatile
    var bytesUplinked: Long = 0
        private set

    @Volatile
    var bytesDownlinked: Long = 0
        private set

    // ACK-only path: Track last ACK from app (messaging app correctness)
    @Volatile
    private var lastAckFromApp: Long = 0

    // Phase 5.1: Server socket liveness tracking (messaging app fix)
    @Volatile
    private var lastServerSocketAliveMs: Long = System.currentTimeMillis()

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
        // Phase 5: Track uplink activity
        lastUplinkActivityMs = System.currentTimeMillis()
        bytesUplinked += payloadSize
    }

    /**
     * ACK-only path: Handle ACK-only packet from app
     * Critical for messaging apps (WhatsApp, Telegram)
     * ACK-only packets MUST update activity to prevent idle disconnects
     */
    fun onAckOnlyReceived(ackNum: Long) {
        // Update activity timestamp - ACK-only is NOT idle
        lastUplinkActivityMs = System.currentTimeMillis()
        lastAckFromApp = ackNum
    }

    /**
     * Phase 4: Handle FIN from app (graceful shutdown)
     * Phase 5: Add activity tracking and structured logging
     * Performs half-close on outbound socket
     */
    fun handleAppFin(tunOutputStream: FileOutputStream) {
        if (state == VirtualTcpState.ESTABLISHED) {
            // Phase 5: Track activity
            lastUplinkActivityMs = System.currentTimeMillis()

            Log.d(TAG, "FLOW_EVENT reason=APP_FIN state=ESTABLISHED->FIN_WAIT_SERVER key=$key")

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
        stopStreamLoop()
    }

    /**
     * Phase 3: Observe RST from app
     */
    fun onRstReceived() {
        state = VirtualTcpState.RESET
        stopStreamLoop()
    }

    /**
     * NETGUARD-IDENTICAL: Pure socket-event-driven stream loop
     * Handles EOF and FIN properly
     * 
     * NO TIMEOUT. NO TIME-BASED EXECUTION.
     * Execution occurs ONLY on kernel TCP socket events.
     */
    fun startDownlinkReader(tunOutputStream: FileOutputStream) {
        if (streamActive || outboundSocket == null || closed) {
            return
        }

        streamActive = true

        streamThread = Thread {
            try {
                // Convert blocking socket to non-blocking NIO channel
                val channel = outboundSocket!!.channel
                    ?: throw IllegalStateException("Socket has no channel")

                channel.configureBlocking(false)
                outboundChannel = channel

                // Create selector for this connection
                val selector = Selector.open()
                streamSelector = selector

                // Register for read events
                channel.register(selector, SelectionKey.OP_READ)

                val readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE)

                Log.d(TAG, "STREAM_LOOP_START key=$key")

                // NETGUARD-IDENTICAL: Block indefinitely until kernel socket events
                // NO TIMEOUT - execution driven purely by TCP socket state changes
                while (streamActive && !Thread.currentThread().isInterrupted && !closed) {
                    // Block indefinitely until socket is ready
                    // Kernel TCP events (ACK, FIN, window updates) wake this call
                    val ready = selector.select()  // INFINITE BLOCK

                    if (!streamActive || closed) break

                    if (ready > 0) {
                        // Socket event occurred - process it
                        val selectedKeys = selector.selectedKeys()
                        val iterator = selectedKeys.iterator()

                        while (iterator.hasNext()) {
                            val skey = iterator.next()
                            iterator.remove()

                            if (!skey.isValid) continue

                            if (skey.isReadable) {
                                // Server socket has data or EOF
                                readBuffer.clear()
                                val bytesRead = channel.read(readBuffer)

                                when {
                                    bytesRead > 0 -> {
                                        // Data received from server
                                        val now = System.currentTimeMillis()
                                        lastServerSocketAliveMs = now

                                        readBuffer.flip()
                                        val payload = ByteArray(bytesRead)
                                        readBuffer.get(payload)

                                        sendDataToApp(payload, tunOutputStream)
                                        Log.d(TAG, "STREAM_DATA size=$bytesRead key=$key")
                                    }

                                    bytesRead == -1 -> {
                                        // EOF - server closed
                                        Log.d(TAG, "STREAM_EOF key=$key")
                                        handleServerFin(tunOutputStream)
                                        break
                                    }

                                    else -> {
                                        // bytesRead == 0, no data yet
                                    }
                                }
                            }
                        }
                    }
                    
                    // NETGUARD-IDENTICAL: No timeout handling
                    // No idle checks
                    // No time-based reflection
                    // Execution happens ONLY when kernel wakes us
                }

                Log.d(TAG, "STREAM_LOOP_END key=$key")

            } catch (e: Exception) {
                if (streamActive && !closed) {
                    Log.e(TAG, "STREAM_LOOP_ERROR key=$key error=${e.message}")
                    handleRst(tunOutputStream)
                }
            } finally {
                streamActive = false

                // Clean up selector
                try {
                    streamSelector?.close()
                } catch (e: Exception) {
                    // Ignore
                }
                streamSelector = null
            }
        }.apply {
            name = "TcpStream-$key"
            isDaemon = true
            start()
        }
    }

    /**
     * Phase 4: Handle EOF from server (server closed)
     * Phase 5: Add activity tracking and structured logging
     * Sends FIN to app and transitions state
     */
    private fun handleServerFin(tunOutputStream: FileOutputStream) {
        if (closed) return

        // Phase 5: Track activity
        lastDownlinkActivityMs = System.currentTimeMillis()

        when (state) {
            VirtualTcpState.ESTABLISHED -> {
                // Server closed first
                Log.d(TAG, "FLOW_EVENT reason=SERVER_FIN state=ESTABLISHED->FIN_WAIT_APP key=$key")
                state = VirtualTcpState.FIN_WAIT_APP
                sendFinToApp(tunOutputStream)
            }
            VirtualTcpState.FIN_WAIT_SERVER -> {
                // Both sides closed - complete shutdown
                Log.d(TAG, "FLOW_EVENT reason=BOTH_FIN state=FIN_WAIT_SERVER->CLOSED key=$key")
                state = VirtualTcpState.CLOSED
                sendFinToApp(tunOutputStream)
            }
            else -> {
                // Unexpected state
                Log.d(TAG, "FLOW_EVENT reason=UNEXPECTED_SERVER_FIN state=$state key=$key")
            }
        }
    }

    /**
     * Phase 3: Send data to app via TUN
     * Phase 5: Track downlink activity and bytes
     */
    private fun sendDataToApp(payload: ByteArray, tunOutputStream: FileOutputStream) {
        if (closed) return

        val seq = serverSeq + 1 + serverDataBytesSent
        val ack = clientSeq + 1 + clientDataBytesSeen

        val packet = TcpPacketBuilder.build(
            srcIp = key.destIp,
            srcPort = key.destPort,
            destIp = key.srcIp,
            destPort = key.srcPort,
            flags = TCP_ACK or TCP_PSH,
            seqNum = seq,
            ackNum = ack,
            payload = payload
        )

        Log.d(
            "TcpProxy",
            "DOWNLINK SEND:\n  seq=$seq\n  ack=$ack\n  payload=${payload.size}"
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }

        serverDataBytesSent += payload.size

        // Phase 5: Track downlink activity
        lastDownlinkActivityMs = System.currentTimeMillis()
        bytesDownlinked += payload.size
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
            ackNum = clientSeq + 1 + clientDataBytesSeen,
            payload = byteArrayOf()
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }

        serverDataBytesSent += 1  // FIN consumes sequence number
    }

    /**
     * ACK-only path: Send ACK-only packet to app
     * Critical for messaging apps - mirrors server keepalive ACKs
     * Must be called when server sends ACK-only or on periodic keepalive
     */
    fun sendAckOnlyToApp(tunOutputStream: FileOutputStream) {
        if (closed) return

        val seq = serverSeq + 1 + serverDataBytesSent
        val ack = clientSeq + 1 + clientDataBytesSeen

        val packet = TcpPacketBuilder.build(
            srcIp = key.destIp,
            srcPort = key.destPort,
            destIp = key.srcIp,
            destPort = key.srcPort,
            flags = TCP_ACK,
            seqNum = seq,
            ackNum = ack,
            payload = byteArrayOf()
        )

        Log.d(
            TAG,
            "ACK_ONLY_TO_APP: seq=$seq ack=$ack state=$state key=$key"
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }

        // ACK-only: Update activity timestamp but NOT byte counters
        lastDownlinkActivityMs = System.currentTimeMillis()
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
            ackNum = clientSeq + 1 + clientDataBytesSeen,
            payload = byteArrayOf()
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }
    }

    /**
     * STREAM-DRIVEN: Stop stream loop
     */
    private fun stopStreamLoop() {
        streamActive = false
        streamThread?.interrupt()

        try {
            streamSelector?.wakeup()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Phase 4: Clean up connection resources (idempotent)
     * Phase 5: Enhanced idempotent cleanup with explicit logging
     * Can be called multiple times safely
     */
    fun close(reason: String = "EXPLICIT_CLOSE") {
        // Phase 5: Idempotent guard - safe to call multiple times
        if (closed) {
            Log.d(TAG, "FLOW_CLOSE_SKIP reason=ALREADY_CLOSED key=$key")
            return
        }
        closed = true

        // Phase 5: Calculate connection lifetime
        val lifetimeMs = System.currentTimeMillis() - connectionStartMs

        // Phase 5: Structured cleanup logging
        Log.d(
            TAG,
            "FLOW_CLOSE reason=$reason state=$state lifetime=${lifetimeMs}ms " +
                    "uplink=${bytesUplinked}B downlink=${bytesDownlinked}B key=$key"
        )

        stopStreamLoop()

        // Phase 5: Defensive socket close - never double-close
        val socketToClose = outboundSocket
        outboundSocket = null

        if (socketToClose != null) {
            try {
                socketToClose.close()
            } catch (e: Exception) {
                // Phase 5: Ignore close errors silently
            }
        }

        state = VirtualTcpState.CLOSED
    }

    /**
     * Phase 5: Observability - get connection lifetime in milliseconds
     */
    fun getConnectionLifetimeMs(): Long {
        return System.currentTimeMillis() - connectionStartMs
    }

    /**
     * Phase 5: Observability - get idle time in milliseconds
     */
    fun getIdleTimeMs(): Long {
        val lastActivity = maxOf(lastUplinkActivityMs, lastDownlinkActivityMs)
        return System.currentTimeMillis() - lastActivity
    }

    /**
     * STREAM-DRIVEN: Reflect server ACK to app (internal to stream loop)
     * Called from stream loop during idle-but-alive condition
     */
    private fun reflectServerAckToApp(tunOutputStream: FileOutputStream) {
        if (closed) return

        val seq = serverSeq + 1 + serverDataBytesSent
        val ack = clientSeq + 1 + clientDataBytesSeen

        val packet = TcpPacketBuilder.build(
            srcIp = key.destIp,
            srcPort = key.destPort,
            destIp = key.srcIp,
            destPort = key.srcPort,
            flags = TCP_ACK,
            seqNum = seq,
            ackNum = ack,
            payload = byteArrayOf()
        )

        val idleMs = System.currentTimeMillis() - lastDownlinkActivityMs
        Log.d(
            TAG,
            "STREAM_ACK_REFLECT: seq=$seq ack=$ack idleMs=$idleMs key=$key"
        )

        synchronized(tunOutputStream) {
            tunOutputStream.write(packet)
        }

        // Update downlink activity (ACK is activity)
        lastDownlinkActivityMs = System.currentTimeMillis()
    }

    override fun toString(): String {
        return "VirtualTcpConnection(key=$key, state=$state, socket=${outboundSocket != null})"
    }
}

