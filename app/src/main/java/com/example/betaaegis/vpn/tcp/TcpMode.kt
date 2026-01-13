package com.example.betaaegis.vpn.tcp

/**
 * Phase 0: TCP Architecture Mode Flag
 *
 * Controls which TCP implementation is active:
 * - false: Packet-based TCP forwarding (current, frozen)
 * - true: User-space TCP proxy (future implementation)
 *
 * RULES:
 * - Default MUST remain false until TCP proxy is fully implemented and tested
 * - Switching to true will redirect all TCP traffic to the proxy engine
 * - Both paths will coexist during transition for debugging and rollback
 *
 * This flag exists to enable safe, phased migration without breaking existing functionality.
 */
object TcpMode {
    /**
     * When false: Use packet-based TCP forwarding (Phase 1-5 implementation)
     * When true: Use TCP proxy engine (future phases)
     *
     * Default: false (packet-based)
     */
    const val USE_TCP_PROXY = false
}

