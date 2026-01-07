package com.example.betaaegis.vpn.policy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3: Rule Engine (Policy Layer)
 *
 * Evaluates rules to decide if a flow should be allowed or blocked.
 *
 * EVALUATION RULE:
 * - Policy is decided ONCE per flow, never per packet
 * - Decision is cached in flow metadata
 * - Mid-stream rule changes do NOT affect existing flows
 *
 * PHASE 3 MINIMAL RULE SET:
 * - Per-UID rules: ALLOW or BLOCK
 * - Default policy: ALLOW (fail-open)
 *
 * ENFORCEMENT:
 * - ALLOW → Create socket, forward traffic
 * - BLOCK → No socket, no packet injection, app times out naturally
 *
 * NON-GOALS (Phase 3):
 * - Domain-based rules (no DNS interception yet)
 * - IP range rules
 * - Time-based rules
 * - Dynamic mid-flow enforcement
 */
class RuleEngine(private val uidResolver: UidResolver) {

    companion object {
        private const val TAG = "RuleEngine"
    }

    // UID-based rules: Map<UID, FlowDecision>
    private val uidRules = ConcurrentHashMap<Int, FlowDecision>()

    // Default policy when no rule matches
    @Volatile
    private var defaultPolicy: FlowDecision = FlowDecision.ALLOW

    /**
     * Evaluate policy for a new flow.
     *
     * This is called ONCE when a flow is created.
     * The decision is cached in the flow object.
     *
     * @param protocol "tcp" or "udp"
     * @param srcIp Source IP
     * @param srcPort Source port
     * @param destIp Destination IP
     * @param destPort Destination port
     * @return FlowDecision (ALLOW or BLOCK)
     */
    fun evaluate(
        protocol: String,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int
    ): FlowDecision {
        // Resolve UID
        val uid = uidResolver.resolveUid(protocol, srcIp, srcPort, destIp, destPort)

        // Check if we have a rule for this UID
        val decision = if (uid != UidResolver.UID_UNKNOWN) {
            uidRules[uid] ?: defaultPolicy
        } else {
            // Unknown UID: default to ALLOW (fail-open)
            // This ensures we don't break apps while UID resolution is imperfect
            defaultPolicy
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Policy for $protocol $srcIp:$srcPort -> $destIp:$destPort: uid=$uid, decision=$decision")
        }

        return decision
    }

    /**
     * Set rule for a specific UID.
     *
     * @param uid App UID
     * @param decision ALLOW or BLOCK
     */
    fun setUidRule(uid: Int, decision: FlowDecision) {
        uidRules[uid] = decision
        Log.i(TAG, "Rule set: UID $uid -> $decision")
    }

    /**
     * Remove rule for a UID (revert to default).
     */
    fun removeUidRule(uid: Int) {
        uidRules.remove(uid)
        Log.i(TAG, "Rule removed: UID $uid")
    }

    /**
     * Set default policy (for UIDs with no specific rule).
     */
    fun setDefaultPolicy(decision: FlowDecision) {
        defaultPolicy = decision
        Log.i(TAG, "Default policy set: $decision")
    }

    /**
     * Get current rule for a UID.
     */
    fun getUidRule(uid: Int): FlowDecision? {
        return uidRules[uid]
    }

    /**
     * Get default policy.
     */
    fun getDefaultPolicy(): FlowDecision {
        return defaultPolicy
    }

    /**
     * Get all UID rules (for UI/telemetry).
     */
    fun getAllRules(): Map<Int, FlowDecision> {
        return uidRules.toMap()
    }

    /**
     * Clear all rules.
     */
    fun clearAllRules() {
        uidRules.clear()
        Log.i(TAG, "All rules cleared")
    }

    /**
     * Get rule engine statistics.
     */
    fun getStats(): String {
        return "Rules: ${uidRules.size} UIDs, default=$defaultPolicy"
    }
}

