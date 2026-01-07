package com.example.betaaegis.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.betaaegis.vpn.tcp.TcpForwarder
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 Update: TUN READER with TCP Forwarding
 *
 * Phase 1: Read and observe packets
 * Phase 2: Route TCP packets to TcpForwarder for stream forwarding
 *
 * IT MUST:
 * - Read raw packets from TUN file descriptor
 * - Route TCP packets to TcpForwarder (Phase 2)
 * - Count packets for telemetry
 * - Log basic activity (periodically, not per-packet)
 * - Never crash on malformed input
 * - Handle interruptions gracefully
 *
 * IT MUST NOT (Phase 2):
 * - Forward UDP (Phase 3)
 * - Perform DNS handling
 * - Parse beyond protocol detection
 * - Make enforcement decisions
 *
 * PACKET HANDLING SEMANTICS (Phase 2):
 * - TCP packets: Forwarded via TcpForwarder (stream-based)
 * - UDP packets: Dropped (Phase 3 will handle)
 * - Other packets: Dropped
 * - NO passive forwarding exists
 */
class TunReader(
    private val vpnInterface: ParcelFileDescriptor,
    private val telemetry: VpnTelemetry,
    private val isRunning: AtomicBoolean,
    private val tcpForwarder: TcpForwarder? = null // Phase 2 addition
) {

    companion object {
        private const val TAG = "TunReader"

        // Buffer size for reading packets
        // 1500 MTU + some headroom for packet headers
        private const val BUFFER_SIZE = 2048

        // Log telemetry every N packets (avoid log spam)
        private const val LOG_INTERVAL = 1000

        // IP Protocol numbers
        private const val IPPROTO_TCP = 6
        private const val IPPROTO_UDP = 17
    }

    /**
     * Main read loop.
     *
     * BLOCKING: This method blocks on read() until data arrives.
     * THREAD: Should be called from a dedicated background thread.
     * INTERRUPTION: Respects Thread.interrupt() for clean shutdown.
     */
    fun run() {
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val buffer = ByteArray(BUFFER_SIZE)
        var consecutiveErrors = 0

        Log.i(TAG, "TunReader started")

        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    // BLOCKING READ: Wait for next packet from TUN interface
                    val length = inputStream.read(buffer)

                    if (length > 0) {
                        // Successfully read a packet
                        handlePacket(buffer, length)
                        consecutiveErrors = 0

                    } else if (length < 0) {
                        // EOF: TUN interface closed
                        Log.i(TAG, "TUN interface closed (EOF)")
                        break
                    }
                    // length == 0 is unusual but not fatal, continue

                } catch (e: IOException) {
                    if (Thread.currentThread().isInterrupted || !isRunning.get()) {
                        // Clean shutdown
                        break
                    }

                    consecutiveErrors++
                    Log.w(TAG, "Read error #$consecutiveErrors: ${e.message}")

                    // If we get too many errors, something is seriously wrong
                    if (consecutiveErrors > 10) {
                        Log.e(TAG, "Too many consecutive errors, stopping reader")
                        break
                    }

                    // Brief pause before retry
                    Thread.sleep(100)

                } catch (e: Exception) {
                    // Catch-all for unexpected errors
                    // MUST NOT CRASH: Log and continue
                    Log.e(TAG, "Unexpected error reading packet: ${e.message}", e)

                    consecutiveErrors++
                    if (consecutiveErrors > 10) {
                        break
                    }
                    Thread.sleep(100)
                }
            }

        } catch (e: InterruptedException) {
            Log.i(TAG, "TunReader interrupted")
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing input stream: ${e.message}")
            }

            Log.i(TAG, "TunReader stopped")
        }
    }

    /**
     * Handle a single packet.
     *
     * Phase 1: Minimal processing (observe only)
     * Phase 2: Route TCP to TcpForwarder, drop others
     *
     * AUTHORITATIVE TCP HANDLING:
     * - TCP packets go to TcpForwarder (one path only)
     * - No "read and ignore" logic
     * - No passive forwarding
     *
     * @param buffer The buffer containing the packet
     * @param length The length of the packet in the buffer
     */
    private fun handlePacket(buffer: ByteArray, length: Int) {
        try {
            // Update telemetry (thread-safe)
            telemetry.recordPacket(length)

            // Periodic logging (avoid spam)
            val packetCount = telemetry.getPacketCount()
            if (packetCount % LOG_INTERVAL == 0L) {
                val snapshot = telemetry.getSnapshot()
                Log.d(TAG, "Telemetry: $snapshot")

                // Log TCP stats if forwarder exists
                tcpForwarder?.let {
                    Log.d(TAG, "TCP: ${it.getStats()}")
                }
            }

            // Phase 2: Route packets based on protocol
            if (tcpForwarder != null) {
                val protocol = getProtocol(buffer, length)

                when (protocol) {
                    IPPROTO_TCP -> {
                        // ONE AND ONLY ONE PATH for TCP
                        // Hand to forwarder - it owns the connection now
                        val packet = buffer.copyOf(length)
                        tcpForwarder.handleTcpPacket(packet)
                    }
                    IPPROTO_UDP -> {
                        // Phase 2: Drop UDP (Phase 3 will handle)
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "UDP packet dropped (not yet supported)")
                        }
                    }
                    else -> {
                        // Unknown protocol - drop
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Unknown protocol: $protocol")
                        }
                    }
                }
            } else {
                // Phase 1 mode: Just observe (for compatibility)
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    logPacketBasics(buffer, length)
                }
            }

        } catch (e: Exception) {
            // TELEMETRY/FORWARDING FAILURES MUST NOT CRASH
            // Log the error but continue processing
            Log.w(TAG, "Packet handling error (non-fatal): ${e.message}")
        }

        // END OF PACKET HANDLING
        // Phase 1: Packet is discarded after observation
        // Phase 2: TCP is forwarded, others are dropped
    }

    /**
     * Get IP protocol number from packet.
     *
     * @return Protocol number, or -1 if invalid
     */
    private fun getProtocol(buffer: ByteArray, length: Int): Int {
        if (length < 20) return -1

        val version = (buffer[0].toInt() and 0xF0) ushr 4
        if (version != 4) return -1 // IPv6 support deferred

        return buffer[9].toInt() and 0xFF
    }

    /**
     * Log basic packet information for debugging.
     * Only called when verbose logging is enabled.
     *
     * IMPORTANT: This does minimal parsing and makes no assumptions
     * about packet validity. Malformed packets are handled gracefully.
     */
    private fun logPacketBasics(buffer: ByteArray, length: Int) {
        try {
            if (length < 1) return

            // Read IP version from first byte (high nibble)
            val version = (buffer[0].toInt() and 0xF0) ushr 4

            when (version) {
                4 -> {
                    if (length >= 20) {
                        val protocol = buffer[9].toInt() and 0xFF
                        Log.v(TAG, "IPv4 packet: length=$length, protocol=$protocol")
                    }
                }
                6 -> {
                    if (length >= 40) {
                        val nextHeader = buffer[6].toInt() and 0xFF
                        Log.v(TAG, "IPv6 packet: length=$length, nextHeader=$nextHeader")
                    }
                }
                else -> {
                    Log.v(TAG, "Unknown packet: version=$version, length=$length")
                }
            }

        } catch (e: Exception) {
            // Even logging must not crash
            // Silently ignore parsing errors
        }
    }
}

