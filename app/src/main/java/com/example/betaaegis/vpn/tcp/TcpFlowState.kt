package com.example.betaaegis.vpn.tcp

/**
 * NetGuard-grade TCP Flow State Machine
 *
 * State transitions:
 * CLOSED -> SYN_SENT: When first SYN seen from app
 * SYN_SENT -> ESTABLISHED: When SYN-ACK sent to app and socket connected
 * ESTABLISHED -> FIN_WAIT_APP: When FIN received from server
 * ESTABLISHED -> FIN_WAIT_SERVER: When FIN received from app
 * FIN_WAIT_APP -> TIME_WAIT: When FIN received from app
 * FIN_WAIT_SERVER -> TIME_WAIT: When FIN received from server
 * TIME_WAIT -> CLOSED: After timeout or final ACK
 * Any -> RESET: When RST received
 * RESET -> CLOSED: Immediately after cleanup
 *
 * NOTE (Phase 0):
 * This enum represents the state machine for packet-based TCP forwarding.
 * It is intentionally preserved for debugging, comparison, and rollback purposes
 * while a TCP proxy is developed in parallel.
 *
 * DO NOT refactor or extend this enum.
 * The TCP proxy will use VirtualTcpState with different semantics.
 */
enum class TcpFlowState {
    /** Initial state, no connection */
    CLOSED,

    /** SYN sent to server, waiting for connection */
    SYN_SENT,

    /** Bidirectional forwarding active */
    ESTABLISHED,

    /** FIN received from server, waiting for app FIN */
    FIN_WAIT_APP,

    /** FIN received from app, waiting for server FIN */
    FIN_WAIT_SERVER,

    /** Both sides closed, waiting for final cleanup */
    TIME_WAIT,

    /** Connection reset, cleanup pending */
    RESET
}

