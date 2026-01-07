package com.example.betaaegis.vpn.tcp

/**
 * Phase 2: TCP Flow State Machine
 *
 * Defines lifecycle states for TCP connections managed by the VPN.
 *
 * State transitions:
 * NEW -> CONNECTING: When outbound socket creation starts
 * CONNECTING -> ESTABLISHED: When socket connects successfully
 * ESTABLISHED -> CLOSING: When FIN/RST received from either side
 * CLOSING -> CLOSED: After cleanup complete
 * Any -> CLOSED: On error or VPN stop
 */
enum class TcpFlowState {
    /** First SYN seen from app */
    NEW,

    /** Outbound socket being created/connected */
    CONNECTING,

    /** Bidirectional forwarding active */
    ESTABLISHED,

    /** FIN/RST observed, graceful shutdown in progress */
    CLOSING,

    /** Cleanup complete, resources released */
    CLOSED
}

