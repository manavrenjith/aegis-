# TCP protect() Failure - Visual Explanation

## The Problem Visualized

### ❌ BEFORE FIX (Self-Excluded Configuration)

```
┌─────────────────────────────────────────────────────────────────┐
│                    Android System State                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  VPN Service: com.example.betaaegis (UID 10123)                 │
│  VPN Network: Active (10.0.0.0/24)                              │
│  Routing Rule: 0.0.0.0/0 → VPN Interface                        │
│  Exception: UID 10123 EXCLUDED from VPN routing ⚠️               │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘

         ↓ App Traffic Flow (Other Apps)

┌─────────────────────────────────────────────────────────────────┐
│  Chrome (UID 10456) → TCP SYN → Route Lookup → VPN → TUN → ✅   │
└─────────────────────────────────────────────────────────────────┘

         ↓ VPN Forwarding Attempt

┌─────────────────────────────────────────────────────────────────┐
│  VPN Service (UID 10123) creates forwarding socket:             │
│                                                                   │
│    val socket = Socket()                                         │
│    val ok = protect(socket)  ← Requests protection              │
│                                                                   │
│  Android AppOps Evaluation:                                      │
│    - Caller: UID 10123 (VPN app)                                │
│    - VPN Owner: UID 10123 ✓                                     │
│    - Routing Policy: UID 10123 EXCLUDED from VPN ⚠️              │
│    - Request: Protect socket (bypass VPN)                       │
│                                                                   │
│  🤔 Conflict Detected:                                           │
│    "App is already excluded from VPN routing.                   │
│     Why is it requesting socket protection (VPN bypass)?        │
│     This creates ambiguous ownership state."                    │
│                                                                   │
│  ❌ Decision: DENY protection request                            │
│                                                                   │
│  Result: protect(socket) returns false                          │
└─────────────────────────────────────────────────────────────────┘

         ↓ TCP Connection Attempt

┌─────────────────────────────────────────────────────────────────┐
│  TCP Stack:                                                      │
│    socket.connect(destination) → Pre-flight routing check       │
│    → Protection status: FALSE ⚠️                                 │
│    → Would create routing loop!                                 │
│    → ❌ ABORT connection                                         │
│                                                                   │
│  Exception: "Failed to protect TCP socket"                      │
└─────────────────────────────────────────────────────────────────┘

         ↓ UDP Forwarding (Why It Works)

┌─────────────────────────────────────────────────────────────────┐
│  UDP Stack:                                                      │
│    sendto(destination) → No pre-flight validation               │
│    → TUN interface forwarding succeeds independently            │
│    → DNS forwarded via TUN, not external socket                 │
│    → ✅ Works despite protection failure                         │
└─────────────────────────────────────────────────────────────────┘


═══════════════════════════════════════════════════════════════════

### ✅ AFTER FIX (Correct Configuration)

```
┌─────────────────────────────────────────────────────────────────┐
│                    Android System State                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  VPN Service: com.example.betaaegis (UID 10123)                 │
│  VPN Network: Active (10.0.0.0/24)                              │
│  Routing Rule: 0.0.0.0/0 → VPN Interface                        │
│  Exception: None (ALL apps including VPN routed to VPN) ✅       │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘

         ↓ App Traffic Flow (All Apps Including VPN)

┌─────────────────────────────────────────────────────────────────┐
│  Chrome (UID 10456) → TCP SYN → Route Lookup → VPN → TUN → ✅   │
│  VPN App (UID 10123) → Default traffic → VPN → TUN → ✅         │
└─────────────────────────────────────────────────────────────────┘

         ↓ VPN Forwarding With Protection

┌─────────────────────────────────────────────────────────────────┐
│  VPN Service (UID 10123) creates forwarding socket:             │
│                                                                   │
│    val socket = Socket()                                         │
│    val ok = protect(socket)  ← Requests protection              │
│                                                                   │
│  Android AppOps Evaluation:                                      │
│    - Caller: UID 10123 (VPN app)                                │
│    - VPN Owner: UID 10123 ✓                                     │
│    - Routing Policy: UID 10123 routes to VPN ✓                  │
│    - Request: Protect socket (bypass VPN for this socket)       │
│                                                                   │
│  ✅ Valid Pattern Detected:                                      │
│    "VPN service is protecting its forwarding socket.            │
│     This is the correct loop-prevention mechanism.              │
│     ALLOW per-socket exemption."                                │
│                                                                   │
│  ✅ Decision: GRANT protection request                           │
│                                                                   │
│  Result: protect(socket) returns true                           │
└─────────────────────────────────────────────────────────────────┘

         ↓ TCP Connection Success

┌─────────────────────────────────────────────────────────────────┐
│  TCP Stack:                                                      │
│    socket.connect(destination) → Pre-flight routing check       │
│    → Protection status: TRUE ✅                                  │
│    → Socket exempt from VPN routing                             │
│    → Uses default routing table                                 │
│    → ✅ Connection succeeds                                      │
│                                                                   │
│  Result: TCP connection established                             │
└─────────────────────────────────────────────────────────────────┘

         ↓ Traffic Flow

┌─────────────────────────────────────────────────────────────────┐
│                                                                   │
│  App → TUN → VPN Service → Protected Socket → Internet          │
│                                                                   │
│  Internet → Protected Socket → VPN Service → TUN → App          │
│                                                                   │
│  ✅ No routing loop                                              │
│  ✅ VPN has full control                                         │
│  ✅ All traffic observed                                         │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Insight: Granular vs Blanket Exemption

### ❌ WRONG: Blanket Self-Exclusion
```kotlin
builder.addDisallowedApplication(packageName)
```
**Effect**: Excludes ENTIRE app from VPN routing  
**Problem**: Creates AppOps ownership conflict  
**Result**: Cannot protect individual forwarding sockets  

### ✅ CORRECT: Per-Socket Protection
```kotlin
val socket = Socket()
vpnService.protect(socket)  // Exempt THIS socket only
```
**Effect**: Exempts SPECIFIC socket from VPN routing  
**Benefit**: VPN still captures app's other traffic  
**Result**: Clean loop prevention with full control  

---

## Protocol Behavior Comparison

### TCP (Connection-Oriented)

```
┌──────────────────────────────────────────────┐
│  connect() Flow                              │
├──────────────────────────────────────────────┤
│  1. socket.connect(destination)              │
│  2. Kernel: Lookup routing table             │
│  3. Kernel: Check socket protection status   │
│  4. IF NOT PROTECTED:                        │
│     → Detect potential VPN loop              │
│     → ABORT with error ❌                     │
│  5. IF PROTECTED:                            │
│     → Use default routing                    │
│     → Begin 3-way handshake ✅               │
└──────────────────────────────────────────────┘

RESULT: Protection failure = Immediate error
```

### UDP (Connectionless)

```
┌──────────────────────────────────────────────┐
│  sendto() Flow                               │
├──────────────────────────────────────────────┤
│  1. socket.sendto(data, destination)         │
│  2. Kernel: Best-effort routing              │
│  3. TUN interface forwarding available?      │
│     → YES: Forward via TUN ✅                 │
│     → DNS works via TUN, not socket          │
│  4. No strict pre-send validation            │
│  5. Protection failure non-fatal             │
└──────────────────────────────────────────────┘

RESULT: Protection failure = TUN forwarding works
```

---

## Root Cause Chain

```
Self-Exclusion Applied
         ↓
AppOps Routing Policy: "UID excluded from VPN"
         ↓
VPN Service Requests Socket Protection
         ↓
AppOps Evaluation: "Conflict detected"
         ↓
Protection Request DENIED
         ↓
protect(socket) returns false
         ↓
TCP connect() Pre-Flight Check
         ↓
Routing Loop Detected
         ↓
Connection ABORTED
         ↓
TCP Connectivity FAILS
```

---

## Fix Chain

```
Remove addDisallowedApplication()
         ↓
AppOps Routing Policy: "UID routes to VPN"
         ↓
VPN Service Requests Socket Protection
         ↓
AppOps Evaluation: "Valid VPN pattern"
         ↓
Protection Request GRANTED
         ↓
protect(socket) returns true
         ↓
TCP connect() Pre-Flight Check
         ↓
Socket Exempt from VPN Routing
         ↓
Connection ESTABLISHED
         ↓
TCP Connectivity WORKS ✅
```

---

## Side-by-Side Comparison

| Aspect | Before Fix ❌ | After Fix ✅ |
|--------|--------------|-------------|
| **VPN Config** | Self-excluded | Self-included |
| **Routing Policy** | UID 10123 bypasses VPN | All UIDs route to VPN |
| **protect() Result** | Returns false | Returns true |
| **AppOps Decision** | Ownership conflict | Valid pattern |
| **TCP Behavior** | Immediate failure | Normal operation |
| **UDP Behavior** | Works via TUN | Works (unchanged) |
| **Loop Prevention** | Failed (blanket exclusion) | Correct (per-socket) |
| **Architecture** | Invalid ownership model | NetGuard-class model |

---

## The Correct Android VPN Pattern

```
┌───────────────────────────────────────────────────────────┐
│  Android VPN Best Practice                                │
├───────────────────────────────────────────────────────────┤
│                                                             │
│  1. Route ALL traffic to VPN (including own app)          │
│     builder.addRoute("0.0.0.0", 0)                        │
│                                                             │
│  2. DO NOT exclude self                                   │
│     ❌ builder.addDisallowedApplication(packageName)       │
│                                                             │
│  3. Protect forwarding sockets individually               │
│     ✅ vpnService.protect(socket)                          │
│                                                             │
│  4. Result: Clean ownership, no conflicts                 │
│                                                             │
└───────────────────────────────────────────────────────────┘

Why This Works:
- VPN owns all routing (clear ownership)
- Per-socket exemption (granular control)
- AppOps validates correctly (no ambiguity)
- TCP validation passes (protected sockets exempt)
- No routing loops (exempted sockets use default route)
```

---

## NetGuard Architectural Principle

> **"A VPN app must be subject to its own VPN routing, with individual forwarding sockets protected to prevent loops."**

This is the industry-standard pattern used by:
- ✅ NetGuard (open-source VPN firewall)
- ✅ AFWall+ (Android firewall)
- ✅ AdGuard (DNS-based VPN)
- ✅ Most commercial VPN firewalls

This project now follows the same proven architecture.

---

**Visual Guide Complete**  
See `TCP_PROTECT_ROOT_CAUSE_ANALYSIS.md` for full technical analysis.

