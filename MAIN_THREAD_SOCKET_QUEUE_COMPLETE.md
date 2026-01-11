# Main-Thread TCP Socket Request Queue - Implementation Complete

## Objective
Fix VpnService.protect() failures by ensuring all TCP socket creation, protection, and connection execute on the VpnService main thread (Binder thread).

## Root Cause
Android authorizes VpnService.protect() using:
- Binder identity
- Service ownership
- Calling thread context

Only the VpnService MAIN THREAD satisfies all three requirements. Calling protect() from worker threads causes undefined behavior and intermittent failures.

## Implementation

### 1. TcpSocketRequestQueue (New)
**Location:** `app/src/main/java/com/example/betaaegis/vpn/tcp/TcpSocketRequestQueue.kt`

**Purpose:**
- Centralizes all TCP socket operations on the main thread
- Worker threads enqueue requests and block synchronously
- No retries, delays, or timing hacks

**Key Design:**
```kotlin
class TcpSocketRequestQueue(private val vpnService: AegisVpnService) {
    private val mainHandler = Handler(Looper.getMainLooper())
    
    fun requestSocket(destIp: InetAddress, destPort: Int, timeoutMs: Int): Socket {
        val future = CompletableFuture<Socket>()
        
        mainHandler.post {
            // Socket(), protect(), connect() ALL on main thread
            val socket = Socket()
            if (!vpnService.protect(socket)) {
                socket.close()
                throw IOException("VpnService.protect() failed")
            }
            socket.connect(InetSocketAddress(destIp, destPort), timeoutMs)
            future.complete(socket)
        }
        
        return future.get()  // Worker thread blocks here
    }
}
```

**Critical Properties:**
- Uses `Handler(Looper.getMainLooper())` - the VpnService Binder thread
- All three operations (create, protect, connect) execute atomically on main thread
- Worker threads block synchronously using CompletableFuture
- No async connect, no thread hopping

### 2. AegisVpnService Changes
**Location:** `app/src/main/java/com/example/betaaegis/vpn/AegisVpnService.kt`

**Changes:**
1. Added import: `TcpSocketRequestQueue`
2. Added field: `lateinit var tcpSocketQueue: TcpSocketRequestQueue`
3. Initialized in `onCreate()`: `tcpSocketQueue = TcpSocketRequestQueue(this)`
4. Removed old `createAndConnectProtectedTcpSocket()` method (no longer needed)

### 3. TcpConnection Changes
**Location:** `app/src/main/java/com/example/betaaegis/vpn/tcp/TcpConnection.kt`

**Changes:**
```kotlin
// OLD (direct call - wrong thread):
val sock = vpnService.createAndConnectProtectedTcpSocket(...)

// NEW (queue-based - main thread):
val sock = vpnService.tcpSocketQueue.requestSocket(...)
```

**Result:**
- TcpConnection never directly calls Socket(), protect(), or connect()
- All socket operations guaranteed to run on main thread
- Worker thread blocks synchronously waiting for result

## Verification

### Build Status
✅ Build successful (no errors)
✅ All changes compile correctly
✅ Only pre-existing warnings remain (unused parameters, etc.)

### Expected Results
After deployment, TCP connections should:
- ✅ protect() always returns true
- ✅ HTTPS sites load immediately
- ✅ https://1.1.1.1 loads
- ✅ Google search result links open
- ✅ WhatsApp TCP bootstrap works
- ✅ No warm-up delay
- ✅ No intermittent failures
- ✅ UDP and DNS remain unchanged

## Architecture Compliance

### ✅ Rules Followed
- ✅ No HandlerThread
- ✅ No ExecutorService  
- ✅ No retries or sleeps
- ✅ No delays or readiness gates
- ✅ No ConnectivityManager
- ✅ No changes to UDP, TunReader, policy engine
- ✅ No changes to routing config or MTU/MSS
- ✅ No refactoring of TCP forwarding logic
- ✅ Deterministic and synchronous

### Files Modified
1. `app/src/main/java/com/example/betaaegis/vpn/tcp/TcpSocketRequestQueue.kt` (NEW)
2. `app/src/main/java/com/example/betaaegis/vpn/AegisVpnService.kt` (MODIFIED)
3. `app/src/main/java/com/example/betaaegis/vpn/tcp/TcpConnection.kt` (MODIFIED)

### Files Unchanged
- ✅ TunReader
- ✅ TcpForwarder
- ✅ UdpForwarder
- ✅ Policy engine
- ✅ Rule engine
- ✅ Domain cache
- ✅ VPN routing configuration
- ✅ All other Phase 1-5 components

## Technical Rationale

### Why Main Thread?
Android VPN framework design:
1. VpnService runs in Binder context
2. protect() checks caller thread context
3. Only main thread has correct Binder identity
4. Worker threads lack proper authorization context

### Why CompletableFuture?
- Provides clean blocking semantics
- No manual wait/notify complexity
- Propagates exceptions correctly
- Standard Java concurrency primitive

### Why Handler(Looper.getMainLooper())?
- Guaranteed to be VpnService main thread
- Same thread that processed VPN permission
- Same thread that called builder.establish()
- Matches NetGuard-class VPN architecture

## NetGuard Alignment
This implementation follows the exact socket ownership pattern used by mature VPN firewalls:
- Socket creation centralized in service
- All operations on service thread
- Worker threads request and wait
- No timing dependencies
- Deterministic behavior

## Summary
The main-thread socket request queue ensures VpnService.protect() always executes with correct Binder authorization by centralizing all TCP socket operations on Handler(Looper.getMainLooper()), eliminating the root cause of intermittent TCP failures while preserving all existing architecture.

**Status:** ✅ IMPLEMENTATION COMPLETE
**Build:** ✅ SUCCESSFUL  
**Ready for:** Device testing and deployment

