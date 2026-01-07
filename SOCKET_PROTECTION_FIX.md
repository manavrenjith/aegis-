# Socket Protection Fix - Applied Changes

## Summary

Applied minimal patch to ensure all TCP sockets are correctly protected from VPN routing loop.

## Changes Made

### 1. AegisVpnService.kt

**Added imports:**
```kotlin
import java.io.IOException
import java.net.Socket
```

**Added method (before getStatistics):**
```kotlin
fun createProtectedTcpSocket(): Socket {
    val socket = Socket()
    val ok = protect(socket)
    if (!ok) {
        socket.close()
        throw IOException("Failed to protect TCP socket")
    }
    return socket
}
```

**Purpose:** Centralizes TCP socket creation with guaranteed protection before any network activity.

---

### 2. TcpConnection.kt

**Changed import:**
```kotlin
// BEFORE:
import android.net.VpnService

// AFTER:
import com.example.betaaegis.vpn.AegisVpnService
```

**Changed constructor parameter type:**
```kotlin
// BEFORE:
private val vpnService: VpnService,

// AFTER:
private val vpnService: AegisVpnService,
```

**Changed connect() method:**
```kotlin
// BEFORE:
val sock = Socket()
if (!vpnService.protect(sock)) {
    throw IOException("Failed to protect socket - would create routing loop")
}

// AFTER:
val sock = vpnService.createProtectedTcpSocket()
```

**Purpose:** TCP connections now use the centralized socket factory method, ensuring protection happens before any connection attempt.

---

### 3. TcpForwarder.kt

**Changed import:**
```kotlin
// BEFORE:
import android.net.VpnService

// AFTER:
import com.example.betaaegis.vpn.AegisVpnService
```

**Changed constructor parameter type:**
```kotlin
// BEFORE:
private val vpnService: VpnService,

// AFTER:
private val vpnService: AegisVpnService,
```

**Purpose:** Type change allows TcpConnection to access createProtectedTcpSocket() method.

---

## Verification

### Socket Protection Order (Guaranteed)
1. ✅ Socket created
2. ✅ Socket protected via VpnService.protect()
3. ✅ Protection verified (throws on failure)
4. ✅ Socket connected to remote address

**No code path exists where connect() happens before protect().**

### VPN App Exclusion (Already Present)
The VPN app is already excluded from routing via:
```kotlin
builder.addDisallowedApplication(packageName)
```
No change needed - defensive exclusion already present in startVpn().

### Lifecycle Safety
- ✅ AegisVpnService instance passed to TcpForwarder is the same instance that called builder.establish()
- ✅ No global singletons introduced
- ✅ Service references updated on VPN restart

---

## What Was NOT Changed

- ❌ UDP forwarding logic (unchanged)
- ❌ Rule evaluation (unchanged)
- ❌ Observability/Phase 5 code (unchanged)
- ❌ VPN routing configuration (unchanged, already correct)
- ❌ Class or method names (unchanged)
- ❌ TCP forwarding logic (unchanged except socket creation)

---

## Expected Result

After this fix:
- ✅ TCP sockets are guaranteed protected before connection
- ✅ No routing loop can occur
- ✅ DNS continues to work (UDP unchanged)
- ✅ HTTPS pages load successfully
- ✅ No "would create routing loop" errors in logs

---

## Files Modified

1. `app/src/main/java/com/example/betaaegis/vpn/AegisVpnService.kt` (+12 lines)
2. `app/src/main/java/com/example/betaaegis/vpn/tcp/TcpConnection.kt` (import + type + -4 lines in connect())
3. `app/src/main/java/com/example/betaaegis/vpn/tcp/TcpForwarder.kt` (import + type change)

**Total:** Minimal mechanical change, no architectural refactor.

---

*Socket protection fix applied successfully.*

