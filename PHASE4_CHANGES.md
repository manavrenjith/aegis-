# Phase 4: What Fundamentally Changed from Phase 3

## One-Paragraph Summary

**Phase 4 adds domain awareness to the VPN by passively observing DNS traffic and caching IP-to-domain mappings, enabling policy rules based on human-readable domain names rather than just UIDs or IP addresses.** DNS queries and responses flowing through the UDP forwarder are inspected (read-only) to extract domain names and associate them with IP addresses using TTL-based expiration. When TCP or UDP flows are created, the destination IP is looked up in this cache to retrieve the domain name (if available), which is then passed to the rule engine for policy evaluation. Domain-based rules are evaluated after UID rules but before the default policy, allowing administrators to block or allow traffic to specific domains (e.g., "ads.example.com") without needing to know the underlying IP addresses. This is entirely control-plane logic—no changes to packet forwarding, socket handling, or TCP/UDP state machines—and all DNS inspection and domain lookups are best-effort, failing gracefully with null values if domains are unknown.

---

## What Changed (Conceptually)

### Phase 3: UID-Based Policy
```
New Flow → Resolve UID → Check UID Rule → Default ALLOW/BLOCK
```

**Limitation:** Can only control traffic by app (UID). Cannot distinguish between different destinations within the same app.

Example: Block Chrome entirely, but cannot block just "ads.example.com" within Chrome.

### Phase 4: UID + Domain-Based Policy
```
New Flow → Resolve UID → Lookup Domain (from DNS cache)
          ↓
          Check UID Rule → Check Domain Rule → Default ALLOW/BLOCK
```

**Enhancement:** Can control traffic by both app (UID) and destination domain.

Example: Allow Chrome, but block "ads.example.com" specifically, even if Chrome tries to connect to it.

---

## What Fundamentally Changed

### 1. DNS is Now Observable

**Before Phase 4:**
- DNS packets forwarded like any other UDP traffic
- No awareness of domain names
- DNS = opaque UDP payload

**After Phase 4:**
- DNS queries parsed to extract domain names
- DNS responses parsed to extract IP → domain mappings
- DNS activity logged (optional telemetry)
- DNS inspection is read-only (never blocks forwarding)

**Impact:** System can now "see" what domains apps are resolving, enabling domain-based decisions.

---

### 2. IP Addresses Have Context

**Before Phase 4:**
- Flows identified by: `srcIP:port → destIP:port`
- No semantic meaning for destination IPs
- Example: `10.0.0.2:12345 → 93.184.216.34:443` (what is 93.184.216.34?)

**After Phase 4:**
- Flows annotated with domain (if known)
- IP addresses linked to human-readable names
- Example: `10.0.0.2:12345 → 93.184.216.34:443 (example.com)`

**Impact:** Logs and policy rules can use domain names instead of memorizing IP addresses.

---

### 3. Policy Has Domain Dimension

**Before Phase 4:**
```kotlin
ruleEngine.evaluate(
    protocol = "tcp",
    srcIp, srcPort, destIp, destPort
)
// Evaluation: UID rule → Default
```

**After Phase 4:**
```kotlin
ruleEngine.evaluate(
    protocol = "tcp",
    srcIp, srcPort, destIp, destPort,
    domain = "example.com" // NEW
)
// Evaluation: UID rule → Domain rule → Default
```

**Impact:** Policy rules can target specific domains regardless of which app makes the request.

---

### 4. Cache as Control-Plane State

**Before Phase 4:**
- Only flow state (TCP connections, UDP pseudo-flows)
- No global shared state between flows

**After Phase 4:**
- DomainCache: Shared, TTL-based IP → domain mappings
- Populated by DNS responses (UDP forwarder)
- Queried by TCP/UDP flow creation
- Cleared on VPN stop

**Impact:** DNS responses from one app can inform policy for another app's connections (shared knowledge).

---

## Example: Domain-Based Blocking

### Scenario: Block all connections to "ads.example.com"

#### Phase 3 Approach (Without Domain Awareness)
1. Manually find all IP addresses for "ads.example.com" (e.g., 10.0.0.1, 10.0.0.2)
2. Create IP-based rules (not supported in Phase 3, would need custom logic)
3. Problem: IP addresses change frequently (CDNs, load balancing)
4. Problem: Cannot distinguish "ads.example.com" from "www.example.com" if they share IPs

**Result:** Not feasible with Phase 3 alone.

#### Phase 4 Approach (With Domain Awareness)
1. Set domain rule: `ruleEngine.setDomainRule("ads.example.com", BLOCK)`
2. Any app that resolves "ads.example.com" → IP is cached
3. When app tries to connect to that IP:
   - Domain lookup finds "ads.example.com"
   - Policy evaluation: Domain rule → BLOCK
   - Connection rejected (TCP: RST, UDP: drop)
4. If IP addresses change, DNS cache updates automatically

**Result:** Simple, effective, dynamic blocking by domain name.

---

## Data Flow Changes

### Before Phase 4: UDP Packet Handling
```
UDP Packet → UdpPacketParser → Create/Lookup Flow → Forward
```

### After Phase 4: UDP Packet Handling with DNS Inspection
```
UDP Packet → UdpPacketParser
    ↓
    Is DNS query (port 53)? → DnsInspector.parseQuery() → Log domain
    Is DNS response (port 53)? → DnsInspector.parseResponse() → Cache IP→domain
    ↓
Create/Lookup Flow (with domain lookup) → Forward
```

**Key Addition:** DNS inspection branch that populates DomainCache (non-blocking).

---

### Before Phase 4: TCP Connection Handling
```
TCP SYN → Extract Flow Key → Check UID Policy → Create Socket or RST
```

### After Phase 4: TCP Connection Handling with Domain Lookup
```
TCP SYN → Extract Flow Key → Lookup Domain (from cache)
    ↓
Check UID Policy → Check Domain Policy → Create Socket or RST
```

**Key Addition:** Domain lookup step before policy evaluation.

---

## Control-Plane vs. Data-Plane

### What Stayed in Data-Plane (Unchanged)
- Packet reading from TUN interface
- TCP socket creation and forwarding
- UDP datagram socket forwarding
- TCP state machine (SYN, ACK, FIN, RST)
- UDP pseudo-flow lifecycle
- Packet construction (TCP/UDP builders)
- Checksum calculation

### What Moved to Control-Plane (New in Phase 4)
- DNS packet parsing (read-only)
- Domain name extraction
- IP → domain caching
- Domain-based rule evaluation
- Domain attribution logging

**Principle:** Phase 4 is control-plane only. It observes and annotates but never alters the data path.

---

## Fail-Safe Philosophy

### Phase 3 Fail-Safe
- Unknown UID → ALLOW (fail-open)
- Policy evaluation failure → ALLOW
- UID resolver crash → Logged, continue

### Phase 4 Fail-Safe (Extended)
- Unknown UID + Unknown domain → ALLOW (fail-open)
- DNS parsing failure → Silently ignore, forward packet normally
- Domain cache miss → Domain is null, policy proceeds without it
- Domain cache write failure → Logged, flow continues
- DNS inspection exception → Logged, forwarding unaffected

**New Guarantee:** DNS inspection and domain attribution never block forwarding, even on errors.

---

## Architectural Boundaries

### Phase 3 Boundaries
```
TunReader (dispatch) → Forwarders (data plane) → RuleEngine (policy)
```

### Phase 4 Boundaries (Extended)
```
TunReader (dispatch) → Forwarders (data plane) ──→ RuleEngine (policy)
                             ↓                          ↑
                       DnsInspector (observe)     Domain lookup
                             ↓                          ↑
                       DomainCache (control plane state)
```

**New Boundary:** DomainCache sits between data plane (forwarders) and control plane (policy), populated by observation and queried by decision-making.

---

## Why This Change is Fundamental

1. **Semantic Shift**: From IP-based to name-based control
   - IPs are transient and opaque
   - Domains are stable and meaningful

2. **Observability**: DNS traffic is now visible
   - Can log what domains apps query
   - Can correlate flows to human-readable names

3. **Policy Expressiveness**: Rules can target domains
   - Block/allow by category (ads, tracking, CDNs)
   - User-friendly rule management (no IP lists)

4. **Cache as Shared Knowledge**: DNS responses inform future flows
   - One DNS response benefits all subsequent connections
   - No per-flow DNS lookup needed

5. **Control-Plane Maturity**: Separation of concerns
   - Data plane: Move packets
   - Control plane: Decide and observe
   - Phase 4 strengthens this separation

---

## What Enables Future Phases

Phase 4's domain awareness enables:

1. **User-Facing Domain UI**
   - Show "Connected to: example.com" instead of "93.184.216.34"
   - Domain-based statistics (bandwidth per domain)

2. **Category-Based Filtering**
   - Block all ".ads" or ".tracker" domains
   - Allowlist/denylist by domain pattern

3. **DNS-Level Features**
   - Custom DNS responses (local DNS server)
   - DNS-based content filtering
   - Parental controls (domain blocklists)

4. **Advanced Policy**
   - Time-based domain rules (block social media after 10 PM)
   - Per-app domain rules (Chrome can access X, but not Y)

5. **Telemetry & Analytics**
   - Top domains per app
   - DNS query frequency analysis
   - Identify apps making excessive DNS queries

**Without Phase 4**, all of the above would require reverse DNS lookups (slow, unreliable) or external databases (privacy concerns).

**With Phase 4**, domain awareness is built-in, real-time, and privacy-preserving (all local).

---

## Summary: The Fundamental Change

**Phase 3 Question:** "Which app is making this connection?"  
**Phase 4 Question:** "Which app is connecting to which domain?"

**Phase 3 Answer:** UID → ALLOW/BLOCK  
**Phase 4 Answer:** (UID, Domain) → ALLOW/BLOCK

**Phase 3 Limitation:** Cannot distinguish connections within the same app.  
**Phase 4 Capability:** Can target specific destinations regardless of app.

**Phase 3 Implementation:** Read /proc/net/tcp for UID attribution.  
**Phase 4 Implementation:** Parse DNS for domain attribution.

**Phase 3 State:** Flow tables (per-connection state).  
**Phase 4 State:** Flow tables + Domain cache (global, TTL-based state).

**Phase 3 Policy Axis:** App identity (UID).  
**Phase 4 Policy Axes:** App identity (UID) + Destination identity (domain).

---

## Conclusion

Phase 4 fundamentally changes the VPN from **IP-aware** to **domain-aware**. This shift enables human-friendly policy rules, better observability, and lays the foundation for advanced filtering features. Crucially, it achieves this without destabilizing the data plane—DNS inspection is purely observational, domain lookups are best-effort, and all failures default to safe behavior (ALLOW). The architecture remains clean, with domain logic isolated to the control plane where it belongs.

