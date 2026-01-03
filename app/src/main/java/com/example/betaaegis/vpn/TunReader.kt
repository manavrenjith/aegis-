package com.example.betaaegis.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 3️⃣ TUN READER RESPONSIBILITIES (STRICT)
 *
 * Phase 1: This class reads raw packets from the TUN interface.
 *
 * IT MUST:
 * - Read raw packets from TUN file descriptor
 * - Count packets for telemetry
 * - Log basic activity (periodically, not per-packet)
 * - Never crash on malformed input
 * - Handle interruptions gracefully
 *
 * IT MUST NOT:
 * - Modify packets
 * - Forward packets
 * - Drop packets intentionally
 * - Perform TCP/UDP logic
 * - Perform checksum logic
 * - Attempt socket operations
 * - Parse packet contents (beyond minimal logging)
 * - Make routing decisions
 * - Implement any enforcement
 *
 * 4️⃣ PACKET HANDLING SEMANTICS
 *
 * "FAIL-OPEN" in Phase 1 means:
 * - Packets are OBSERVED, not controlled
 * - No enforcement decisions are applied
 * - No data-plane ownership is assumed
 * - If telemetry fails, packets are still read (and discarded)
 *
 * WHY THIS PHASE IS INTENTIONALLY INCOMPLETE:
 * - We establish capture infrastructure first
 * - Forwarding logic comes in future phases
 * - Correctness here means "do less, not more"
 * - Prevents premature optimization and hidden assumptions
 */
class TunReader(
    private val vpnInterface: ParcelFileDescriptor,
    private val telemetry: VpnTelemetry,
    private val isRunning: AtomicBoolean
) {

    companion object {
        private const val TAG = "TunReader"

        // Buffer size for reading packets
        // 1500 MTU + some headroom for packet headers
        private const val BUFFER_SIZE = 2048

        // Log telemetry every N packets (avoid log spam)
        private const val LOG_INTERVAL = 1000
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
     * Phase 1: Minimal processing
     * - Update telemetry
     * - Periodic logging
     * - NO packet modification
     * - NO forwarding
     * - NO enforcement
     *
     * @param buffer The buffer containing the packet
     * @param length The length of the packet in the buffer
     */
    private fun handlePacket(buffer: ByteArray, length: Int) {
        try {
            // 5️⃣ MINIMAL TELEMETRY
            // Update counters (thread-safe)
            telemetry.recordPacket(length)

            // Periodic logging (avoid spam)
            val packetCount = telemetry.getPacketCount()
            if (packetCount % LOG_INTERVAL == 0L) {
                val snapshot = telemetry.getSnapshot()
                Log.d(TAG, "Telemetry: $snapshot")
            }

            // Optional: Extract minimal packet info for debugging
            // Only enabled in verbose logging mode
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logPacketBasics(buffer, length)
            }

        } catch (e: Exception) {
            // TELEMETRY FAILURES MUST NOT AFFECT PACKET HANDLING
            // Log the error but continue processing
            Log.w(TAG, "Telemetry error (non-fatal): ${e.message}")
        }

        // END OF PACKET HANDLING
        // In Phase 1, we simply discard the packet after observation
        // Future phases will add forwarding logic here
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

