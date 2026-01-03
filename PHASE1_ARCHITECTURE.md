# Phase 1: Full-Capture VPN Architecture

## Overview

Phase 1 establishes a **visibility-only** VPN foundation that captures ALL app traffic without forwarding or enforcement.

```
┌─────────────────────────────────────────────────────────────┐
│                      Android Apps                           │
│  (Browser, Messenger, Games, System Services, etc.)        │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ All network traffic
                 │ (TCP, UDP, ICMP)
                 ▼
┌─────────────────────────────────────────────────────────────┐
│                    Android Kernel                           │
│                   Routing Decision                          │
│                                                             │
│  Route Table: 0.0.0.0/0 → VPN Interface                    │
│               ::/0 → VPN Interface                          │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ All packets routed to TUN
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│              TUN Interface (10.0.0.2/24)                    │
│              File Descriptor: /dev/tunX                     │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ Raw IP packets
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│                     TunReader Thread                        │
│                  (Blocking Read Loop)                       │
│                                                             │
│  while(running) {                                           │
│    packet = read(tunFd)                                     │
│    observe(packet)                                          │
│    count(packet)                                            │
│    // NO FORWARDING                                         │
│  }                                                          │
└────────────────┬────────────────────────────────────────────┘
                 │
                 │ Telemetry data
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│                    VpnTelemetry                             │
│                  (Thread-Safe Counters)                     │
│                                                             │
│  • Total Packets: 12,345                                    │
│  • Total Bytes: 5.2 MB                                      │
│  • Last Packet: 123ms ago                                   │
└─────────────────────────────────────────────────────────────┘
                 │
                 │ UI updates
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│                     MainActivity                            │
│                   (Jetpack Compose)                         │
│                                                             │
│  [Start VPN] [Stop VPN]                                     │
│                                                             │
│  Status: Active                                             │
│  Packets: 12,345                                            │
└─────────────────────────────────────────────────────────────┘
```

## Key Architectural Decisions

### 1. Full-Capture Configuration

**VpnService.Builder Configuration:**
```kotlin
Builder()
    .setSession("Aegis VPN Phase 1")
    .addAddress("10.0.0.2", 24)        // VPN interface IPv4
    .addAddress("fd00:1:2::1", 64)     // VPN interface IPv6
    .addRoute("0.0.0.0", 0)            // Capture ALL IPv4
    .addRoute("::", 0)                 // Capture ALL IPv6
    .addDnsServer("8.8.8.8")           // Required for establishment
    .setMtu(1500)                      // Standard Ethernet MTU
    .setBlocking(true)                 // Blocking reads
    .addDisallowedApplication(packageName) // Prevent routing loop
```

**Why NO per-app routing rules:**
- `addAllowedApplication()` is NOT used
- `addDisallowedApplication()` is ONLY used for self-bypass
- This ensures ALL apps route into VPN
- Creates complete visibility foundation

**What traffic is captured:**
- All IPv4 traffic (0.0.0.0/0)
- All IPv6 traffic (::/0)
- TCP, UDP, ICMP protocols
- All apps except VPN app itself

**Why this is future-safe:**
- No enforcement logic to migrate later
- Clean separation: capture now, forward later
- No hidden kernel forwarding assumptions
- Explicit architectural boundaries

### 2. TUN Interface Establishment

**Process:**
1. Request VPN permission (user dialog)
2. Configure Builder with routes
3. Call `establish()` to create TUN interface
4. Obtain ParcelFileDescriptor (file descriptor)
5. Start background thread to read from FD
6. Start foreground service with notification

**Constraints:**
- Read loop is BLOCKING (not polling)
- No writing to TUN interface yet
- No assumptions about packet validity
- Thread interruption for clean shutdown

### 3. TunReader Responsibilities

**MUST DO:**
- ✓ Read raw packets from TUN FD
- ✓ Count packets for telemetry
- ✓ Log basic activity (periodically)
- ✓ Never crash on malformed input
- ✓ Handle interruptions gracefully

**MUST NOT DO:**
- ✗ Modify packets
- ✗ Forward packets
- ✗ Drop packets intentionally
- ✗ Perform TCP/UDP logic
- ✗ Perform checksum logic
- ✗ Attempt socket operations
- ✗ Parse packet contents (beyond minimal logging)
- ✗ Make routing decisions
- ✗ Implement any enforcement

### 4. Packet Handling Semantics

**"Fail-Open" in Phase 1:**
- Packets are OBSERVED, not controlled
- No enforcement decisions applied
- No data-plane ownership assumed
- If telemetry fails, packets still read (then discarded)

**Why this phase is intentionally incomplete:**
- Establishes capture infrastructure first
- Forwarding logic comes in future phases
- Correctness = "do less, not more"
- Prevents premature optimization
- Avoids hidden assumptions

**Result:** Internet connectivity will NOT work in Phase 1 (acceptable)

### 5. Minimal Telemetry

**Tracked Metrics:**
- Total packets seen (counter)
- Total bytes seen (counter)
- Timestamp of last packet (timestamp)

**Rules:**
- Thread-safe (atomic operations)
- Never affects packet handling
- Failures don't affect VPN stability
- No blocking operations
- No external dependencies

## Files/Classes Created

```
app/src/main/java/com/example/betaaegis/
├── MainActivity.kt                    # VPN control UI
└── vpn/
    ├── AegisVpnService.kt            # VPN service (lifecycle + config)
    ├── TunReader.kt                  # TUN reading loop
    └── VpnTelemetry.kt               # Thread-safe counters
```

**AegisVpnService.kt** (237 lines)
- VPN service lifecycle management
- VpnService.Builder configuration
- TUN interface establishment
- Foreground service with notification
- Clean start/stop semantics

**TunReader.kt** (220 lines)
- Background thread for TUN reading
- Blocking read loop
- Error handling (never crash)
- Minimal packet observation
- Telemetry integration

**VpnTelemetry.kt** (125 lines)
- Thread-safe atomic counters
- Packet/byte counting
- Timestamp tracking
- Snapshot API for UI

**MainActivity.kt** (192 lines)
- Jetpack Compose UI
- VPN permission request
- Start/Stop controls
- Status display

## Explicit Non-Goals (Phase 1)

Phase 1 **DOES NOT** include:

- ❌ TCP stream handling
- ❌ UDP forwarding
- ❌ Rule enforcement
- ❌ UID resolution
- ❌ Flow tables
- ❌ DNS logic
- ❌ Packet reinjection
- ❌ Checksum calculations
- ❌ Socket operations
- ❌ Routing decisions
- ❌ Performance optimization
- ❌ Internet connectivity

These are intentionally deferred to future phases.

## Success Criteria

Phase 1 is **complete** when:

✅ App installs successfully  
✅ VPN permission dialog appears  
✅ VPN starts and stays running  
✅ Packets are observed from multiple apps  
✅ No crashes occur under normal usage  
✅ No routing-based logic exists in code  
✅ Telemetry shows packet counts  
✅ Clean start/stop behavior  

⚠️ Internet connectivity may NOT work (acceptable in Phase 1)

## Testing Phase 1

### Installation Test
```bash
./gradlew installDebug
```

### Verification Steps

1. **Launch app**
   - Tap "Start VPN"
   - System permission dialog appears
   - Tap "OK"

2. **Verify VPN active**
   - Status shows "Active"
   - Notification appears: "Aegis VPN Active"
   - VPN icon in status bar

3. **Generate traffic**
   - Open browser app
   - Try to load website (may fail - expected)
   - Open messenger app
   - Check system updates

4. **Check logs**
   ```bash
   adb logcat -s AegisVPN TunReader
   ```
   
   Expected output:
   ```
   AegisVPN: VPN started successfully
   TunReader: TunReader started
   TunReader: Telemetry: packets=1000, bytes=524288 (0.50 MB), lastPacket=45ms ago
   TunReader: Telemetry: packets=2000, bytes=1048576 (1.00 MB), lastPacket=12ms ago
   ```

5. **Stop VPN**
   - Tap "Stop VPN"
   - Status shows "Inactive"
   - No crashes
   - Final telemetry logged

## Why Phase 1 is Necessary

**Before any packet forwarding can be implemented correctly, we must:**

1. **Establish reliable packet capture** - Prove that ALL traffic enters the VPN and is observable. This validates the routing configuration and eliminates blind spots.

2. **Build stable infrastructure** - Create service lifecycle, threading model, and error handling that won't need major refactoring when forwarding is added.

3. **Validate architectural boundaries** - Confirm clean separation between capture (Phase 1), forwarding (Phase 2), and enforcement (Phase 3).

4. **Avoid premature complexity** - Implementing forwarding before capture is proven leads to debugging nightmares where it's unclear if packets are failing at capture or forwarding.

5. **Set correct expectations** - A visibility-only phase establishes that this VPN is NOT a simple "forward everything" proxy, but a carefully controlled inspection system.

**Phase 1 proves the hardest part: getting packets IN. Everything else builds on this foundation.**

## Next Phases (Future)

- **Phase 2:** Packet forwarding (TCP/UDP sockets)
- **Phase 3:** UID attribution
- **Phase 4:** Rule engine
- **Phase 5:** DNS inspection
- **Phase 6:** Performance optimization

But for now: **Phase 1 is complete.**

