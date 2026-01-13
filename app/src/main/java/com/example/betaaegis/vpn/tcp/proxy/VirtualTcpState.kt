package com.example.betaaegis.vpn.tcp.proxy

/**
 * Phase 4: Virtual TCP Connection State
 *
 * State machine for user-space TCP proxy connections.
 * Phase 4 adds complete lifecycle management with FIN handling.
 *
 * State transitions:
 * CLOSED -> SYN_SEEN: When SYN packet observed from app
 * SYN_SEEN -> ESTABLISHED: After handshake emulation complete
 * ESTABLISHED -> FIN_WAIT_SERVER: When FIN received from app
 * ESTABLISHED -> FIN_WAIT_APP: When EOF from server socket
 * FIN_WAIT_SERVER -> CLOSED: When EOF from server (both sides closed)
 * FIN_WAIT_APP -> CLOSED: When FIN received from app (both sides closed)
 * Any -> RESET: On RST or error
 */
enum class VirtualTcpState {
    /**
     * Initial state - no connection exists
     */
    CLOSED,

    /**
     * SYN packet observed from app
     * Waiting for handshake to complete
     */
    SYN_SEEN,

    /**
     * Virtual connection established with app
     * Bidirectional data forwarding active
     */
    ESTABLISHED,

    /**
     * Phase 4: FIN received from app
     * Waiting for server to close (half-close)
     */
    FIN_WAIT_SERVER,

    /**
     * Phase 4: EOF from server socket
     * Waiting for app to close
     */
    FIN_WAIT_APP,

    /**
     * Connection reset or error
     * Cleanup in progress
     */
    RESET
}

