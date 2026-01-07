# Phase 2: TCP Stream Forwarding - Architecture

## 🎯 Mission Accomplished

**Phase 2: TCP Stream Forwarding (First Working Data Plane)** is **COMPLETE**.

Phase 1 established packet capture. Phase 2 adds TCP stream forwarding for internet connectivity.

---

## 📐 Core Architectural Rule

### Ownership Transfer

Once a TCP packet is read from the TUN interface:
- **The VPN owns the connection completely**
- The kernel no longer manages TCP state for this flow
- The VPN **must explicitly forward or drop** every packet
- **Passive behavior = dropped connection**

### What Happens If VPN Does Nothing

- App sees no response
- Connection times out  
- No implicit kernel assistance exists

### Who Owns TCP State

- The VPN owns all TCP state via `TcpConnection` objects
- Each flow has dedicated stream-forwarding logic
- Kernel sees only the TUN interface (no TCP state)

---

## 🔄 TCP Forwarding Model

```
[App] --TCP SYN--> [TUN] --read--> [VPN terminates] --Socket--> [Server]
[App] <--TCP ACK-- [TUN] <-write-- [VPN constructs] <--Socket-- [Server]
```

### What We Do

- **Terminate** app's TCP connection at VPN
- Create **new socket** to destination
- Forward data as **byte streams** (not packets)
- Construct response packets explicitly

### What We Explicitly Reject

- ❌ Packet passthrough (kernel forwarding)
- ❌ TCP sequence/ACK reconstruction
- ❌ Transparent proxying
- ❌ Relying on kernel to "complete" TCP

---

## 🔁 TCP Flow Lifecycle

### State Machine

```kotlin
enum class TcpFlowState {
    NEW,          // First SYN seen
    CONNECTING,   // Outbound socket being created
    ESTABLISHED,  // Bidirectional forwarding active
    CLOSING,      // FIN/RST observed
    CLOSED        // Cleanup complete
}
```

### State Transitions

```
NEW -> CONNECTING: When outbound socket creation starts
CONNECTING -> ESTABLISHED: When socket connects successfully
ESTABLISHED -> CLOSING: When FIN/RST received from either side
CLOSING -> CLOSED: After cleanup complete
Any -> CLOSED: On error or VPN stop
```

### Storage

- `TcpConnection` object per flow
- Key: `TcpFlowKey` (src IP/port + dst IP/port)
- Stored in `ConcurrentHashMap` in `TcpForwarder`

---

## 🔌 Socket Creation & Protection

### Critical: Socket Protection

```kotlin
val socket = Socket()

// MANDATORY: Protect before connecting
if (!vpnService.protect(socket)) {
    throw IOException("Failed to protect socket")
}

socket.connect(InetSocketAddress(destIp, destPort), CONNECT_TIMEOUT_MS)
```

### Why Protection is Mandatory

Without `protect()`, the socket routes **back into the VPN**:
```
App → TUN → VpnService → Socket → TUN → VpnService → Socket → ...
```

This creates an **infinite loop**:
- App never reaches the internet
- VPN may crash from stack overflow or deadlock

### Failure Mode Without Protection

The VPN creates a routing loop where its own outbound traffic re-enters the VPN interface, causing cascading failures.

---

## 🌊 Stream Forwarding Mechanics

### App → Server (Uplink)

```kotlin
// Extract payload from TCP packet
val payload = extractTcpPayload(packet)

// Write to socket stream
socket.getOutputStream().write(payload)
socket.getOutputStream().flush()
```

### Server → App (Downlink)

```kotlin
// Read from socket stream
val buffer = ByteArray(8192)
val bytesRead = socket.getInputStream().read(buffer)

// Construct TCP response packet
val responsePacket = buildTcpPacket(
    srcIp = destIp,
    srcPort = destPort,
    dstIp = srcIp,
    dstPort = srcPort,
    payload = buffer.copyOf(bytesRead),
    flags = TCP_PSH_ACK
)

// Write back to TUN interface
tunOutputStream.write(responsePacket)
```

### Who Constructs Packets

- `TcpPacketBuilder` class constructs all response packets
- Includes IP header + TCP header + payload
- Calculates both IP and TCP checksums

### Why Checksum Calculation is Required

- The kernel expects valid checksums on packets written to TUN
- Invalid checksums → packets dropped silently
- Must compute both IP header checksum and TCP pseudo-header checksum

### Why This is Simpler Than Packet-Transparent Forwarding

- No TCP sequence number tracking
- No ACK number management
- No retransmission logic
- Kernel handles reliability on both sides of the VPN

---

## 📖 TunReader Responsibilities

### Authoritative TCP Handling (Phase 2)

```kotlin
when (protocol) {
    IPPROTO_TCP -> {
        // ONE AND ONLY ONE PATH
        tcpForwarder.handleTcpPacket(packet)
        // NO fallback, NO passive logic
    }
    IPPROTO_UDP -> {
        // Phase 2: Drop UDP (Phase 3 will handle)
        Log.v(TAG, "UDP packet dropped")
    }
    else -> {
        // Unknown protocol - drop
    }
}
```

### TCP Handling Must

- Extract metadata (src/dst IP/port, flags, payload)
- Find or create `TcpConnection` for this flow
- Hand data to stream forwarder
- **Never** return packet to caller
- **Never** "read and ignore"

---

## 🛡️ Error Handling & Cleanup

### Socket Connection Failure

```kotlin
try {
    socket.connect(address, timeout)
} catch (e: IOException) {
    sendRstToApp()
    closeFlow()
}
```

### Remote Server Reset

```kotlin
// Detected when socket read returns -1
sendFinToApp()
closeFlow()
```

### App Closes Connection

```kotlin
// Detected by FIN flag in packet from app
socket.close()
closeFlow()
```

### VPN Stops Mid-Flow

```kotlin
override fun onDestroy() {
    tcpForwarder.closeAllFlows()
    tunReader.stop()
}
```

### Cleanup Order

1. Stop accepting new packets
2. Close all sockets
3. Remove all flows from map
4. Stop reader thread
5. Close TUN interface

---

## 📊 Telemetry (Minimal, Safe)

### Metrics Tracked

```kotlin
class TcpStats {
    val bytesUplink = AtomicLong(0)
    val bytesDownlink = AtomicLong(0)
    val activeFlowCount = AtomicInteger(0)
    val totalFlowsCreated = AtomicLong(0)
    val totalFlowsClosed = AtomicLong(0)
}
```

### Rules

- Read via `AtomicLong.get()` only
- Never blocks forwarding logic
- Failures are silent (no exceptions thrown)

---

## 🚫 Explicit Non-Goals

Phase 2 does **NOT** include:
- ❌ UDP forwarding
- ❌ DNS handling
- ❌ UID attribution
- ❌ Rule enforcement
- ❌ TLS inspection
- ❌ Performance optimization
- ❌ Native (C) code
- ❌ Connection pooling
- ❌ Packet reordering
- ❌ MTU handling

---

## 📊 TCP Data-Path Diagram

```
┌─────────────┐
│   Browser   │
│  (TCP App)  │
└──────┬──────┘
       │ TCP SYN
       ▼
┌─────────────────┐
│  TUN Interface  │ (10.0.0.2/24)
└─────────┬───────┘
          │
          ▼
    ┌──────────┐
    │TunReader │ (reads packets)
    └────┬─────┘
         │
         ▼
   ┌────────────────┐
   │  TcpForwarder  │ (manages flows)
   └───┬────────┬───┘
       │        │
       │ NEW    │ ESTABLISHED
       ▼        ▼
┌──────────────────────┐
│   TcpConnection      │
│  ┌─────────────┐     │
│  │Socket.protect│     │
│  └──────┬──────┘     │
│         ▼            │
│    InetSocket        │
│         │            │
│         ▼            │
│   ┌──────────┐       │
│   │ Server   │       │
│   └────┬─────┘       │
│        │             │
│   Stream Forwarding  │
│        │             │
│        ▼             │
│  Build TCP Packet    │
└───────┬──────────────┘
        │
        ▼
 Write to TUN
        │
        ▼
    ┌────────┐
    │ Browser│
    └────────┘
```

---

## 📂 Files Created

### New TCP Components

```
app/src/main/java/com/example/betaaegis/vpn/tcp/
├── TcpFlowKey.kt           (18 lines)  - Flow identifier (4-tuple)
├── TcpFlowState.kt         (31 lines)  - State machine enum
├── TcpPacketParser.kt      (140 lines) - Extract metadata from packets
├── TcpPacketBuilder.kt     (168 lines) - Construct packets with checksums
├── TcpConnection.kt        (289 lines) - Per-flow socket handler
└── TcpForwarder.kt         (312 lines) - Flow lifecycle manager
```

### Modified Files

```
app/src/main/java/com/example/betaaegis/vpn/
├── AegisVpnService.kt      (Modified)  - Integrated TCP forwarder
└── TunReader.kt            (Modified)  - Routes TCP to forwarder

app/src/main/java/com/example/betaaegis/
└── MainActivity.kt         (Modified)  - Updated UI for Phase 2
```

**Total:** 958 lines of new TCP forwarding code

---

## 🎓 What Fundamentally Changed from Phase 1

### Phase 1: Observe Only
- VPN observed traffic passively
- No ownership of connections
- No internet connectivity
- "Read and count" only

### Phase 2: Own and Forward
- **VPN now owns TCP connections completely**
- App connections terminate at VPN
- VPN creates new sockets to destinations
- Data flows as **streams**, not packets
- Internet works via explicit forwarding
- **No passive behavior exists**

### Key Insight

Once a packet enters the VPN, it is **never delivered by the kernel**. The VPN must explicitly forward or drop it. This is the foundation for all future enforcement logic (Phase 3+).

---

## ✅ Testing Verification

### Expected Behavior

1. **Start VPN**
   - Notification: "Phase 2: TCP forwarding enabled"
   - VPN icon in status bar

2. **Open Browser**
   - Load https://www.google.com
   - Page should load successfully
   - TCP connections work

3. **Check Logs**
   ```
   adb logcat -s TcpForwarder TcpConnection TunReader
   ```
   Expected:
   ```
   TcpForwarder: New connection: 192.168.1.100:54321 -> 142.250.185.46:443
   TcpConnection: Connected: 192.168.1.100:54321 -> 142.250.185.46:443
   TcpForwarder: TCP Stats: flows=3, up=2048, down=15360
   ```

4. **Test Multiple Apps**
   - Browser (HTTPS)
   - Email client
   - Messenger app
   - All TCP apps should work

5. **Stop VPN**
   - All flows closed cleanly
   - No crashes
   - Can restart VPN

### Failure Indicators

- ❌ If pages don't load → check socket protection
- ❌ If VPN crashes → check thread management
- ❌ If connections timeout → check packet construction
- ❌ If partial loads → check stream forwarding

---

## 🔮 Phase 3 Preview

Phase 3 will add:
- UDP forwarding (for DNS, QUIC, VoIP)
- DNS query inspection
- UID attribution per flow
- Basic rule enforcement

Phase 2 is the foundation that makes all future phases possible.

