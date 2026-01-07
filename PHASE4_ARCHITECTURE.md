# Phase 4: DNS Inspection & Domain-Based Policy - Architecture

## Overview

Phase 4 adds DNS inspection and domain-based policy rules to the VPN without modifying the TCP/UDP forwarding model. This is a **control-plane only** enhancement.

**Key Principle:** Domain logic observes, annotates, and decides at flow creation time. Never mid-stream.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Android Apps                             │
│  (Browser, Email, Messengers, etc.)                             │
└────────────┬────────────────────────────────────────────────────┘
             │
             │ All traffic routed into VPN
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                  TUN Interface (10.0.0.2/24)                     │
│                  File Descriptor: /dev/tunX                      │
└────────────┬────────────────────────────────────────────────────┘
             │
             │ Raw IP packets
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      TunReader Thread                            │
│                   (Phase 1: Unchanged)                           │
│                                                                  │
│  Protocol dispatch:                                              │
│  • TCP → TcpForwarder                                           │
│  • UDP → UdpForwarder                                           │
└─────────────┬───────────────────────┬────────────────────────────┘
              │                       │
              │ TCP                   │ UDP
              │                       │
              ▼                       ▼
┌──────────────────────────┐  ┌──────────────────────────────────┐
│     TcpForwarder         │  │       UdpForwarder               │
│   (Phase 2/3/4)          │  │     (Phase 3/4)                  │
│                          │  │                                  │
│  On new connection:      │  │  On new UDP flow:                │
│  1. Lookup domain        │  │  1. DNS inspection (port 53)     │
│  2. Evaluate policy      │  │     • Parse DNS query            │
│  3. Create socket        │  │     • Parse DNS response         │
│     or send RST          │  │     • Cache IP → domain          │
│                          │  │  2. Lookup domain                │
│                          │  │  3. Evaluate policy              │
│                          │  │  4. Create socket or drop        │
└──────────────────────────┘  └──────────────────────────────────┘
              │                       │
              │                       │
              └───────┬───────────────┘
                      │
                      ▼
         ┌────────────────────────────┐
         │       DomainCache          │
         │   (Phase 4: NEW)           │
         │                            │
         │  IP → Domain mapping       │
         │  • TTL-based expiration    │
         │  • Thread-safe cache       │
         │  • Best-effort lookup      │
         └────────────┬───────────────┘
                      │
                      │ domain (optional)
                      │
                      ▼
         ┌────────────────────────────┐
         │       RuleEngine           │
         │   (Phase 3: Extended)      │
         │                            │
         │  Evaluation order:         │
         │  1. UID rule (if exists)   │
         │  2. Domain rule (if exists)│
         │  3. Default ALLOW          │
         └────────────────────────────┘
                      │
                      │ FlowDecision (ALLOW/BLOCK)
                      │
                      ▼
         ┌────────────────────────────┐
         │   Socket Creation          │
         │                            │
         │  • ALLOW: Create socket    │
         │  • BLOCK: No socket        │
         │           App times out    │
         └────────────────────────────┘
```

---

## DNS Inspection Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    App makes DNS query                           │
│                   (e.g., "example.com")                          │
└────────────┬────────────────────────────────────────────────────┘
             │
             │ UDP packet to 8.8.8.8:53
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      UdpForwarder                                │
│                                                                  │
│  1. Parse UDP packet → UdpMetadata                              │
│  2. Check if destPort == 53 (DNS)                               │
│  3. If DNS query:                                               │
│     • DnsInspector.parseQuery()                                 │
│     • Log domain name (optional)                                │
│  4. Forward packet normally                                     │
│     (inspection NEVER blocks forwarding)                        │
└────────────┬────────────────────────────────────────────────────┘
             │
             │ Forward to DNS server
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DNS Server (8.8.8.8)                          │
│                  Processes DNS query                             │
└────────────┬────────────────────────────────────────────────────┘
             │
             │ UDP response from 8.8.8.8:53
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      UdpForwarder                                │
│                                                                  │
│  1. Parse UDP packet → UdpMetadata                              │
│  2. Check if srcPort == 53 (DNS response)                       │
│  3. If DNS response:                                            │
│     • DnsInspector.parseResponse()                              │
│     • Extract DNS records (A/AAAA)                              │
│     • For each record with IP:                                  │
│       DomainCache.put(ip, domain, ttl)                          │
│  4. Forward packet to app                                       │
│     (inspection NEVER blocks forwarding)                        │
└────────────┬────────────────────────────────────────────────────┘
             │
             │ Response delivered to app
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                  DomainCache Updated                             │
│                                                                  │
│  "93.184.216.34" → "example.com" (TTL: 3600s)                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Domain Attribution Flow

```
┌─────────────────────────────────────────────────────────────────┐
│         App opens TCP connection to 93.184.216.34:443           │
└────────────┬────────────────────────────────────────────────────┘
             │
             │ TCP SYN packet
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      TcpForwarder                                │
│                                                                  │
│  handleNewConnection():                                          │
│                                                                  │
│  1. Extract flow key (src IP:port, dest IP:port)                │
│  2. Lookup domain:                                              │
│     domain = domainCache.get("93.184.216.34")                   │
│     → "example.com" (or null if not cached)                     │
│                                                                  │
│  3. Evaluate policy:                                            │
│     decision = ruleEngine.evaluate(                             │
│       protocol = "tcp",                                          │
│       srcIp, srcPort, destIp, destPort,                         │
│       domain = "example.com"                                     │
│     )                                                            │
│                                                                  │
│  4. Policy evaluation order:                                    │
│     a. UID rule (if exists) → return decision                   │
│     b. Domain rule (if exists) → return decision                │
│     c. Default ALLOW                                            │
│                                                                  │
│  5. Enforcement:                                                │
│     • ALLOW: Create socket, connect, forward                    │
│     • BLOCK: Send RST to app, no socket created                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Components

### New Components (Phase 4)

#### 1. DnsMessage.kt
**Purpose:** Data classes for DNS queries and responses  
**Responsibilities:**
- Define DNS query/response structures
- Minimal representation (transaction ID, domain, type, TTL, IP)
- Read-only data classes

**Key Types:**
- `DnsQueryType`: A (IPv4), AAAA (IPv6), CNAME, OTHER
- `DnsQuery`: Parsed DNS query
- `DnsRecord`: Single DNS response record
- `DnsResponse`: Complete DNS response with records

#### 2. DnsInspector.kt
**Purpose:** Parse DNS packets (read-only)  
**Responsibilities:**
- Parse DNS queries from UDP payload
- Parse DNS responses from UDP payload
- Extract domain names using DNS label encoding
- Handle DNS compression pointers

**Critical Rules:**
- Inspection NEVER blocks forwarding
- Parsing failures are silently ignored
- No EDNS, DNSSEC, or advanced features
- Scope: DNS over UDP (port 53) only

**Key Functions:**
- `isDns(destPort)`: Check if packet is DNS
- `parseQuery(payload)`: Extract domain from query
- `parseResponse(payload)`: Extract IP → domain mappings

#### 3. DomainCache.kt
**Purpose:** Map IP addresses to domain names  
**Responsibilities:**
- Cache IP → domain mappings from DNS responses
- TTL-based expiration (30s to 1 hour)
- Thread-safe concurrent access
- Best-effort lookup (returns null if not found)

**Key Functions:**
- `put(ipAddress, domain, ttl)`: Cache mapping
- `get(ipAddress)`: Lookup domain (null if missing/expired)
- `cleanup()`: Passive cleanup of expired entries
- `clear()`: Clear all entries (on VPN stop)

**Behavior:**
- Never blocks on cache misses
- Cache failures do not affect forwarding
- Expired entries are removed lazily

### Modified Components (Phase 4)

#### 4. RuleEngine.kt (Extended)
**Changes:**
- Added `domainRules: Map<String, FlowDecision>` for domain-based rules
- Extended `evaluate()` to accept optional `domain: String?` parameter
- New evaluation order: UID rule → Domain rule → Default

**New Methods:**
- `setDomainRule(domain, decision)`: Add domain rule
- `removeDomainRule(domain)`: Remove domain rule
- `getDomainRule(domain)`: Get rule for domain
- `getAllDomainRules()`: Get all domain rules

**Evaluation Order:**
1. Check UID rule (highest priority)
2. Check domain rule (if domain provided)
3. Default ALLOW

#### 5. UdpForwarder.kt (Extended)
**Changes:**
- Added `domainCache: DomainCache` parameter
- Added `inspectDnsQuery()`: Parse and log DNS queries
- Added `inspectDnsResponse()`: Parse responses and cache domains
- Modified `handleUdpPacket()`: Inspect DNS on port 53
- Modified `createFlow()`: Lookup domain and pass to policy

**DNS Inspection:**
- Inspects packets to/from port 53
- Queries: Log domain name (optional telemetry)
- Responses: Cache IP → domain mappings
- Inspection happens before/after forwarding (non-blocking)

#### 6. TcpForwarder.kt (Extended)
**Changes:**
- Added `domainCache: DomainCache?` parameter
- Modified `handleNewConnection()`: Lookup domain before policy evaluation
- Pass domain to `ruleEngine.evaluate()`

**Domain Lookup:**
- Best-effort: `domain = domainCache?.get(destIp)`
- Null if cache miss or no cache
- Logged for observability

#### 7. AegisVpnService.kt (Extended)
**Changes:**
- Added `domainCache: DomainCache?` field
- Initialize `DomainCache()` in `startTunReader()`
- Pass `domainCache` to both TCP and UDP forwarders
- Clear `domainCache` in `stopVpn()`

**Lifecycle:**
- Created on VPN start
- Cleared on VPN stop
- Shared between TCP/UDP forwarders

---

## Phase 1-3 Preservation

### What Was NOT Changed

✅ **TunReader**: No changes (still dispatches packets)  
✅ **TCP Forwarding Logic**: Socket creation, stream forwarding unchanged  
✅ **UDP Forwarding Logic**: Datagram forwarding unchanged  
✅ **Policy Evaluation Timing**: Still once per flow  
✅ **Packet Handling**: No new mutation or rewriting  
✅ **VPN Configuration**: Builder settings unchanged  

### What Was Extended (Safely)

- **RuleEngine**: Added domain rule map (backward compatible)
- **Forwarders**: Added optional domain lookup (null-safe)
- **Policy Call**: Added optional domain parameter (defaults to null)

---

## Policy Evaluation Order

```
New Flow Created (TCP SYN or UDP first packet)
│
├─ 1. Resolve UID (Phase 3: /proc/net/tcp or udp)
│   → UID or UID_UNKNOWN
│
├─ 2. Lookup Domain (Phase 4: DomainCache)
│   → Domain string or null
│
├─ 3. Evaluate Policy (RuleEngine.evaluate)
│   │
│   ├─ Check UID rule
│   │  • If UID known and rule exists → return decision
│   │
│   ├─ Check Domain rule (Phase 4)
│   │  • If domain known and rule exists → return decision
│   │
│   └─ Default policy
│      • Return ALLOW (fail-open)
│
└─ 4. Enforcement
   ├─ ALLOW: Create socket, forward traffic
   └─ BLOCK: No socket (TCP: send RST, UDP: drop silently)
```

---

## Fail-Safe Behavior

### DNS Inspection Failures
- **Malformed DNS packet**: Silently ignored, forwarding continues
- **Parsing exception**: Logged at VERBOSE level, forwarding continues
- **Cache write failure**: Logged, flow proceeds without domain

### Domain Lookup Failures
- **Cache miss**: Domain is null, policy evaluated without domain
- **Null domainCache**: Flows proceed normally (backward compatible)
- **Cache read exception**: Domain is null, no impact on enforcement

### Policy Evaluation
- **Unknown UID + No domain**: Default ALLOW
- **Unknown UID + Known domain**: Domain rule or ALLOW
- **Known UID + No domain**: UID rule or ALLOW
- **No matching rules**: ALLOW

---

## Example Scenarios

### Scenario 1: Browser opens website with DNS

```
1. App queries DNS: "example.com" → 8.8.8.8:53
   • UdpForwarder inspects query, logs "example.com"
   • Forwards query to DNS server

2. DNS response: "example.com" → 93.184.216.34 (TTL: 3600)
   • UdpForwarder inspects response
   • DomainCache.put("93.184.216.34", "example.com", 3600)
   • Forwards response to app

3. App opens TCP: 93.184.216.34:443
   • TcpForwarder.handleNewConnection()
   • domain = domainCache.get("93.184.216.34") → "example.com"
   • decision = ruleEngine.evaluate(..., domain="example.com")
   • If domain rule exists: apply it
   • Otherwise: check UID or default ALLOW
```

### Scenario 2: App connects to IP directly (no DNS)

```
1. App opens TCP: 1.2.3.4:443
   • TcpForwarder.handleNewConnection()
   • domain = domainCache.get("1.2.3.4") → null (not in cache)
   • decision = ruleEngine.evaluate(..., domain=null)
   • Check UID rule or default ALLOW
   • Flow proceeds normally
```

### Scenario 3: Domain rule blocks connection

```
1. Admin sets rule: setDomainRule("ads.example.com", BLOCK)

2. App queries DNS: "ads.example.com" → 10.0.0.1
   • DNS response cached: "10.0.0.1" → "ads.example.com"

3. App opens TCP: 10.0.0.1:80
   • domain = "ads.example.com"
   • decision = evaluate(..., domain="ads.example.com") → BLOCK
   • TcpForwarder sends RST to app
   • No socket created
   • Connection fails immediately
```

---

## Telemetry & Observability

### DNS Activity
- DNS queries logged at DEBUG level (domain name, query type)
- DNS responses logged at DEBUG level (domain → IP, TTL)
- Cache updates logged at DEBUG level

### Domain Attribution
- Flow creation logs include domain (if known)
- Example: `"New TCP connection: 93.184.216.34:443 (domain: example.com)"`

### Policy Decisions
- Policy evaluation logs include domain
- Example: `"Policy: tcp 10.0.0.2:12345 -> 93.184.216.34:443 (example.com): uid=10123 -> ALLOW (domain rule)"`

### Statistics
- `DomainCache.getStats()`: Cache entry count
- `RuleEngine.getStats()`: UID rules, domain rules, default policy

---

## Non-Goals (Explicitly Out of Scope)

❌ **DNS blocking mid-stream**: DNS packets always forwarded  
❌ **DNS poisoning/modification**: No packet mutation  
❌ **DoT (DNS over TLS)**: Not inspected  
❌ **DoH (DNS over HTTPS)**: Not inspected  
❌ **TLS SNI inspection**: TLS is opaque  
❌ **Packet content inspection**: Beyond DNS in UDP  
❌ **Performance optimization**: Correctness over speed  
❌ **Native (C) code**: Pure Kotlin implementation  

---

## Files Created/Modified

### New Files (Phase 4)

```
app/src/main/java/com/example/betaaegis/vpn/dns/
├── DnsMessage.kt         52 lines   DNS data classes
├── DnsInspector.kt      265 lines   DNS parser (read-only)
└── DomainCache.kt       130 lines   IP → domain cache
```

**Total:** 447 lines of new DNS/domain code

### Modified Files (Phase 4)

```
app/src/main/java/com/example/betaaegis/vpn/
├── policy/
│   └── RuleEngine.kt         +70 lines   Domain rule support
├── tcp/
│   └── TcpForwarder.kt       +15 lines   Domain lookup for TCP
├── udp/
│   └── UdpForwarder.kt       +80 lines   DNS inspection + domain lookup
└── AegisVpnService.kt        +30 lines   Initialize DomainCache
```

**Total:** ~195 lines of integration code

### UI Updates

```
app/src/main/java/com/example/betaaegis/
└── MainActivity.kt           +5 lines    Updated UI text for Phase 4
```

---

## Build & Test Results

✅ **BUILD SUCCESSFUL in 12s**  
✅ No compilation errors  
✅ Only benign warnings (unused methods for future API, VpnService usage)  
✅ Phase 1-3 code unchanged except for integration points  

---

## Why This Phase is Necessary Before Future Phases

**Phase 4 establishes domain awareness without destabilizing the data plane.**

1. **Control-Plane Isolation**: DNS inspection is read-only, never affects forwarding
2. **Best-Effort Attribution**: Domain lookup failures don't break flows
3. **Policy Extension Point**: Domain rules integrate cleanly with existing UID rules
4. **Observability Foundation**: Logs now show human-readable domains
5. **Future-Ready**: Later phases can use domain metadata for:
   - Advanced filtering (HTTPS intercept by domain)
   - Per-domain bandwidth accounting
   - Domain-based routing rules
   - User-visible domain activity logs

**Without Phase 4**, adding domain awareness later would require:
- Retrofitting domain lookups into established flows (complex)
- Mid-stream policy changes (violates Phase 3 guarantee)
- Cache coherency issues across forwarders

**With Phase 4**, domain attribution is:
- Available from flow creation
- Consistent across TCP/UDP
- Fail-safe and backward compatible

---

## Summary

Phase 4 adds **DNS inspection** (read-only) and **domain-based policy** without modifying TCP/UDP forwarding.

**Key Achievements:**
- DNS queries/responses parsed from UDP packets
- IP → domain mappings cached with TTL
- Domain names associated with flows at creation
- Domain-based ALLOW/BLOCK rules evaluated
- Policy order: UID → Domain → Default
- Fail-open on all DNS/cache errors
- Phase 1-3 code preserved

**What Changed:**
- RuleEngine: Added domain rule map
- Forwarders: Added domain lookup on new flows
- UDP: Added DNS inspection hooks
- Service: Initialize and clear DomainCache

**What Did NOT Change:**
- TunReader: Still only dispatches
- TCP/UDP forwarding: Socket handling unchanged
- Policy timing: Still once per flow
- Enforcement model: Still fail-open

Phase 4 is **complete** and **ready for testing**.

