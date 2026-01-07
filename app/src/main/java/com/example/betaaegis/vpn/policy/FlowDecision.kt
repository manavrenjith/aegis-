package com.example.betaaegis.vpn.policy

/**
 * Phase 3: Flow Policy Decision
 *
 * Binary decision for flow handling.
 * Decided ONCE per flow, never re-evaluated.
 *
 * ALLOW: Forward traffic via sockets
 * BLOCK: Drop silently, no socket creation
 */
enum class FlowDecision {
    /**
     * Forward this flow normally.
     * Create sockets, forward data bidirectionally.
     */
    ALLOW,

    /**
     * Do not forward this flow.
     * No socket creation, no packet injection.
     * App will timeout naturally.
     */
    BLOCK
}

