package com.example.betaaegis.vpn.policy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3: Rule Engine (Policy Layer)
 * Phase 4: Extended with Domain-Based Rules
 *
 * Evaluates rules to decide if a flow should be allowed or blocked.
 *
 * EVALUATION RULE:
 * - Policy is decided ONCE per flow, never per packet
 * - Decision is cached in flow metadata
 * - Mid-stream rule changes do NOT affect existing flows
 *
 * PHASE 3 RULE SET:
 * - Per-UID rules: ALLOW or BLOCK
 * - Default policy: ALLOW (fail-open)
 *
 * PHASE 4 ADDITIONS:
 * - Domain-based rules: ALLOW or BLOCK by domain
 * - Domain attribution is best-effort (may be null)
 * - No blocking based solely on missing domain
 *
 * EVALUATION ORDER:
 * 1. App UID rule (if exists)
 * 2. Domain rule (if domain known and rule exists)
 * 3. Default ALLOW
 *
 * ENFORCEMENT:
 * - ALLOW → Create socket, forward traffic
 * - BLOCK → No socket, no packet injection, app times out naturally
 *
 * NON-GOALS:
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

    // Phase 4: Domain-based rules: Map<Domain, FlowDecision>
    private val domainRules = ConcurrentHashMap<String, FlowDecision>()

    // Default policy when no rule matches
    @Volatile
    private var defaultPolicy: FlowDecision = FlowDecision.ALLOW

    /**
     * Evaluate policy for a new flow.
     *
     * This is called ONCE when a flow is created.
     * The decision is cached in the flow object.
     *
     * Phase 4: Accepts optional domain for domain-based rules.
     *
     * EVALUATION ORDER:
     * 1. UID rule (if exists)
     * 2. Domain rule (if domain provided and rule exists)
     * 3. Default policy
     *
     * @param protocol "tcp" or "udp"
     * @param srcIp Source IP
     * @param srcPort Source port
     * @param destIp Destination IP
     * @param destPort Destination port
     * @param domain Optional domain name (from DNS cache)
     * @return FlowDecision (ALLOW or BLOCK)
     */
    fun evaluate(
        protocol: String,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        domain: String? = null
    ): FlowDecision {
        // Resolve UID
        val uid = uidResolver.resolveUid(protocol, srcIp, srcPort, destIp, destPort)

        // 1. Check UID rule first (highest priority)
        if (uid != UidResolver.UID_UNKNOWN) {
            val uidDecision = uidRules[uid]
            if (uidDecision != null) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Policy: $protocol $srcIp:$srcPort -> $destIp:$destPort ($domain): uid=$uid -> $uidDecision (UID rule)")
                }
                return uidDecision
            }
        }

        // 2. Check domain rule (Phase 4)
        if (domain != null) {
            val domainDecision = domainRules[domain]
            if (domainDecision != null) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Policy: $protocol $srcIp:$srcPort -> $destIp:$destPort ($domain): uid=$uid -> $domainDecision (domain rule)")
                }
                return domainDecision
            }
        }

        // 3. Default policy
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Policy: $protocol $srcIp:$srcPort -> $destIp:$destPort ($domain): uid=$uid -> $defaultPolicy (default)")
        }

        return defaultPolicy
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
     * Phase 4: Set rule for a specific domain.
     *
     * @param domain Domain name (e.g., "example.com")
     * @param decision ALLOW or BLOCK
     */
    fun setDomainRule(domain: String, decision: FlowDecision) {
        domainRules[domain] = decision
        Log.i(TAG, "Rule set: Domain $domain -> $decision")
    }

    /**
     * Phase 4: Remove rule for a domain (revert to default).
     */
    fun removeDomainRule(domain: String) {
        domainRules.remove(domain)
        Log.i(TAG, "Rule removed: Domain $domain")
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
     * Phase 4: Get current rule for a domain.
     */
    fun getDomainRule(domain: String): FlowDecision? {
        return domainRules[domain]
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
    fun getAllUidRules(): Map<Int, FlowDecision> {
        return uidRules.toMap()
    }

    /**
     * Phase 4: Get all domain rules (for UI/telemetry).
     */
    fun getAllDomainRules(): Map<String, FlowDecision> {
        return domainRules.toMap()
    }

    /**
     * Clear all rules.
     */
    fun clearAllRules() {
        uidRules.clear()
        domainRules.clear()
        Log.i(TAG, "All rules cleared")
    }

    /**
     * Get rule engine statistics.
     */
    fun getStats(): String {
        return "Rules: ${uidRules.size} UIDs, ${domainRules.size} domains, default=$defaultPolicy"
    }
}

