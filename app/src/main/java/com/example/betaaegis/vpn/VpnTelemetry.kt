package com.example.betaaegis.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * 5️⃣ MINIMAL TELEMETRY (SAFE)
 *
 * Phase 1: Basic traffic visibility metrics.
 *
 * WHAT IS TRACKED:
 * - Total packets seen
 * - Total bytes seen
 * - Timestamp of last packet
 *
 * RULES:
 * - Telemetry must NEVER affect packet handling
 * - Telemetry failures must NOT affect VPN stability
 * - All operations are thread-safe (atomic)
 * - No blocking operations
 * - No external dependencies
 *
 * This is intentionally minimal. Future phases may add:
 * - Per-UID counters
 * - Per-protocol breakdown
 * - Flow tracking
 * - Rate calculations
 *
 * But Phase 1 establishes only the foundation.
 */
class VpnTelemetry {

    // Thread-safe counters
    private val packetCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)
    private val lastPacketTimestamp = AtomicLong(0)

    /**
     * Record a packet observation.
     *
     * THREAD-SAFE: Can be called from TunReader thread
     * NON-BLOCKING: Uses atomic operations only
     * FAILURE-SAFE: No exceptions thrown
     *
     * @param packetLength The length of the packet in bytes
     */
    fun recordPacket(packetLength: Int) {
        packetCount.incrementAndGet()
        byteCount.addAndGet(packetLength.toLong())
        lastPacketTimestamp.set(System.currentTimeMillis())
    }

    /**
     * Get current packet count.
     *
     * @return Total number of packets observed
     */
    fun getPacketCount(): Long = packetCount.get()

    /**
     * Get current byte count.
     *
     * @return Total number of bytes observed
     */
    fun getByteCount(): Long = byteCount.get()

    /**
     * Get timestamp of last packet.
     *
     * @return Timestamp in milliseconds since epoch, or 0 if no packets yet
     */
    fun getLastPacketTimestamp(): Long = lastPacketTimestamp.get()

    /**
     * Get a snapshot of all telemetry data.
     *
     * ATOMIC: Values may be inconsistent if called during active traffic,
     * but each individual value is atomically read.
     *
     * @return TelemetrySnapshot containing current metrics
     */
    fun getSnapshot(): TelemetrySnapshot {
        return TelemetrySnapshot(
            packetCount = packetCount.get(),
            byteCount = byteCount.get(),
            lastPacketTimestamp = lastPacketTimestamp.get()
        )
    }

    /**
     * Reset all counters.
     *
     * NOTE: Rarely needed, but useful for testing or stats reset.
     */
    fun reset() {
        packetCount.set(0)
        byteCount.set(0)
        lastPacketTimestamp.set(0)
    }
}

/**
 * Immutable snapshot of telemetry data.
 *
 * This provides a consistent view of metrics at a point in time.
 */
data class TelemetrySnapshot(
    val packetCount: Long,
    val byteCount: Long,
    val lastPacketTimestamp: Long
) {
    /**
     * Human-readable representation.
     */
    override fun toString(): String {
        val mb = byteCount / (1024.0 * 1024.0)
        val timeSinceLastPacket = if (lastPacketTimestamp > 0) {
            System.currentTimeMillis() - lastPacketTimestamp
        } else {
            0L
        }

        return "packets=$packetCount, bytes=$byteCount (${String.format("%.2f", mb)} MB), " +
               "lastPacket=${timeSinceLastPacket}ms ago"
    }
}

