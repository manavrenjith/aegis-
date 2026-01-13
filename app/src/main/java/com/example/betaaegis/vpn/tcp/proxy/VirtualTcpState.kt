package com.example.betaaegis.vpn.tcp.proxy

/**
 * Phase 1: Virtual TCP Connection State
 *
 * State machine for user-space TCP proxy connections.
 * This differs from packet-based TcpFlowState because it represents
 * a virtual connection terminated by the VPN, not a forwarded flow.
 *
 * State transitions (Phase 1 - observation only):
 * CLOSED -> SYN_SEEN: When SYN packet observed from app
 * SYN_SEEN -> ESTABLISHED: (Reserved for Phase 2 - after handshake emulation)
 * ESTABLISHED -> FIN_WAIT: (Reserved for Phase 2 - on FIN)
 * Any -> RESET: On RST or error
 *
 * Phase 1: Only CLOSED and SYN_SEEN are used (observation only)
 */
enum class VirtualTcpState {
    /**
     * Initial state - no connection exists
     */
    CLOSED,

    /**
     * SYN packet observed from app
     * Phase 1: Just tracking, no handshake yet
     */
    SYN_SEEN,

    /**
     * Virtual connection established with app
     * Reserved for Phase 2+
     */
    ESTABLISHED,

    /**
     * FIN received, graceful shutdown in progress
     * Reserved for Phase 2+
     */
    FIN_WAIT,

    /**
     * Connection reset or error
     */
    RESET
}

