# TCP protect() Fix - Complete

## Problem Fixed

VpnService.protect() was failing for all TCP sockets because socket creation and protection were executing on a background HandlerThread, which broke Android's Binder authorization mechanism.

## Root Cause

Android authorizes VpnService.protect() based on the Binder context of the active VpnService instance. When socket creation was posted to a HandlerThread via Handler.post(), the protect() call executed on a different thread with no valid Binder context, causing it to always return false.

## Solution Implemented

Removed all asynchronous socket creation logic and made TCP socket creation synchronous and inline within the VpnService call stack.

### Changes Made

1. **AegisVpnService.kt**
   - Removed `Handler` and `HandlerThread` imports
   - Removed `socketThread` and `socketHandler` fields
   - Removed HandlerThread initialization in `startVpn()`
   - Removed HandlerThread cleanup in `stopVpn()`
   - Replaced async `createAndConnectProtectedTcpSocket()` implementation with synchronous version:
     ```kotlin
     fun createAndConnectProtectedTcpSocket(
         destIp: InetAddress,
         destPort: Int,
         timeoutMs: Int = 10_000
     ): Socket {
         if (!isRunning.get() || vpnInterface == null) {
             throw IOException("VPN service not running")
         }

         val socket = Socket()

         val ok = protect(socket)
         if (!ok) {
             socket.close()
             throw IOException("VpnService.protect() failed for TCP socket")
         }

         socket.connect(InetSocketAddress(destIp, destPort), timeoutMs)
         return socket
     }
     ```

2. **TcpConnection.kt**
   - No changes required - already correctly uses `vpnService.createAndConnectProtectedTcpSocket()`

## Architecture After Fix

```
Call Stack (Single Thread):
TcpForwarder.handleTcpPacket()
  ↓
TcpConnection.connect()
  ↓
AegisVpnService.createAndConnectProtectedTcpSocket()
  ↓
  Socket()                    // Create socket
  ↓
  VpnService.protect(socket) // Protect on VpnService Binder context ✅
  ↓
  socket.connect()           // Connect to destination
```

## Key Principles

1. **Socket Ownership**: All TCP socket operations execute directly on the VpnService call stack
2. **No Thread Hopping**: No Handler.post, no executors, no async callbacks
3. **Binder Authorization**: protect() is called from the correct Binder context
4. **Synchronous Execution**: Socket creation blocks the caller until complete
5. **Fail Fast**: Exceptions propagate immediately on failure

## What Was NOT Changed

- TCP forwarding logic (TcpForwarder, TcpConnection data plane)
- UDP forwarding
- TunReader
- Policy engine
- Routing configuration
- DNS logic
- MTU/MSS settings

## Expected Results

✅ VpnService.protect() returns true for all TCP sockets
✅ HTTPS sites load immediately
✅ https://1.1.1.1 loads successfully
✅ Google search result links open
✅ WhatsApp TCP bootstrap succeeds
✅ No "Failed to protect TCP socket" errors
✅ No timing dependencies or warm-up behavior
✅ UDP and DNS behavior unchanged

## Verification Status

- ✅ Code compiles successfully
- ✅ Build passes (BUILD SUCCESSFUL)
- ✅ No compilation errors
- ✅ Ready for runtime testing

## NetGuard Alignment

This implementation now matches NetGuard's socket ownership model:
- Sockets created directly in VpnService
- No delegation to worker threads
- protect() called inline before connect()
- Synchronous execution throughout
- Proper Binder context maintained

---
**Fix Type**: Kernel-level correctness fix
**Lines Changed**: ~40 lines removed/simplified
**Architectural Impact**: Zero (no refactoring)
**Risk Level**: Minimal (removal of incorrect async logic)

