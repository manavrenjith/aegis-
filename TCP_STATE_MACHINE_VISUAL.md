# TCP State Machine - Visual Flow

```
                                    ┌──────────┐
                                    │  CLOSED  │
                                    └─────┬────┘
                                          │
                         SYN from app     │ connect()
                                          ▼
                                    ┌──────────┐
                                    │ SYN_SENT │
                                    └─────┬────┘
                                          │
                     Socket connected +   │ sendSynAck()
                     SYN-ACK sent to app  │
                                          ▼
                    ┌─────────────────────────────────────────┐
                    │            ESTABLISHED                  │
                    │  ✓ Accept ALL ACK packets               │
                    │  ✓ Accept data with/without PSH         │
                    │  ✓ NO sequence validation               │
                    │  ✓ NEVER send RST for data              │
                    │  ✓ TLS/HTTPS works here                 │
                    └────┬────────────────────────┬───────────┘
                         │                        │
           FIN from      │                        │  FIN from
           server        │                        │  app
                         ▼                        ▼
                  ┌─────────────┐          ┌──────────────┐
                  │ FIN_WAIT_   │          │  FIN_WAIT_   │
                  │    APP      │          │   SERVER     │
                  └──────┬──────┘          └──────┬───────┘
                         │                        │
           FIN from app  │                        │ FIN from server
                         └────────┬───────────────┘
                                  ▼
                            ┌──────────┐
                            │TIME_WAIT │
                            └─────┬────┘
                                  │
                            Timeout/cleanup
                                  ▼
                            ┌──────────┐
                            │  CLOSED  │
                            └──────────┘

                         ╔════════════════╗
                         ║  RST Handling  ║
                         ╚════════════════╝
                                  │
                    RST from app or server
                                  │
                                  ▼
                            ┌──────────┐
                            │  RESET   │
                            └─────┬────┘
                                  │
                              Immediate
                                  ▼
                            ┌──────────┐
                            │  CLOSED  │
                            └──────────┘
```

## Critical State: ESTABLISHED

### What Happens in ESTABLISHED

```
┌─────────────────────────────────────────────────────────────┐
│  App → VPN → Server                                         │
│  ═══════════════════════                                    │
│                                                              │
│  1. App sends data (ACK + payload)                          │
│     ↓                                                        │
│  2. TcpForwarder receives packet                            │
│     ↓                                                        │
│  3. Lookup flow → TcpConnection found                       │
│     ↓                                                        │
│  4. Check state == ESTABLISHED ✓                            │
│     ↓                                                        │
│  5. Call handleEstablishedPacket()                          │
│     ↓                                                        │
│  6. Accept ACK (no validation)                              │
│     ↓                                                        │
│  7. Extract payload                                         │
│     ↓                                                        │
│  8. Write to socket.outputStream                            │
│     ↓                                                        │
│  9. Data forwarded to server ✓                              │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Server → VPN → App                                         │
│  ═══════════════════════                                    │
│                                                              │
│  1. Downlink thread reads from socket.inputStream           │
│     ↓                                                        │
│  2. Data received from server                               │
│     ↓                                                        │
│  3. Build TCP packet (PSH+ACK + payload)                    │
│     ↓                                                        │
│  4. Set seq = nextSeqToApp                                  │
│     ↓                                                        │
│  5. Set ack = nextAckToServer                               │
│     ↓                                                        │
│  6. Write packet to TUN interface                           │
│     ↓                                                        │
│  7. Increment nextSeqToApp += bytes                         │
│     ↓                                                        │
│  8. App receives data ✓                                     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### What Does NOT Happen in ESTABLISHED

```
❌ NO RST sent for unexpected sequence numbers
❌ NO RST sent for TLS data
❌ NO RST sent for ServerHello
❌ NO RST sent for certificates
❌ NO RST sent for reordered packets
❌ NO RST sent for data without PSH flag
❌ NO strict sequence validation
❌ NO ACK number validation
❌ NO window size checks
❌ NO packet rejection
```

## Packet Dispatch Logic

```
Packet arrives from TUN
        │
        ▼
   Parse TCP metadata
        │
        ├─→ RST? ──→ handleRst() → close flow
        │
        ├─→ SYN (no ACK)? ──→ New connection
        │                      │
        │                      ├─→ Flow exists? → Ignore (duplicate SYN)
        │                      └─→ Flow missing? → handleNewConnection()
        │
        ├─→ FIN? ──→ Flow exists?
        │            │
        │            ├─→ Yes → handleAppFin()
        │            │          │
        │            │          └─→ State = TIME_WAIT? → close flow
        │            │
        │            └─→ No → sendRstForKey() (FIN for unknown flow)
        │
        └─→ ACK? ──→ Flow exists?
                     │
                     ├─→ Yes → Check state
                     │         │
                     │         ├─→ ESTABLISHED → handleEstablishedPacket()
                     │         │                  │
                     │         │                  └─→ ✓ Accept packet
                     │         │                       ✓ Forward payload
                     │         │                       ✓ Never RST
                     │         │
                     │         ├─→ FIN_WAIT_* → Accept ACK, forward payload
                     │         │
                     │         └─→ Other states → Ignore silently (NO RST)
                     │
                     └─→ No → Has payload?
                               │
                               ├─→ Yes → sendRstForKey() (data for unknown flow)
                               └─→ No → Ignore (pure ACK for unknown flow)
```

## TLS Handshake Through VPN

```
App (Browser)          VPN (TcpConnection)        Server
     │                           │                    │
     │ SYN                       │                    │
     ├──────────────────────────→│                    │
     │                           │ connect()          │
     │                           ├───────────────────→│
     │                           │                    │
     │                     SYN-ACK                    │
     │←──────────────────────────┤                    │
     │ State: SYN_SENT → ESTABLISHED                 │
     │                           │                    │
     │ ACK (handshake complete)  │                    │
     ├──────────────────────────→│                    │
     │                           │                    │
     │ ClientHello (TLS)         │                    │
     ├──────────────────────────→│──────────────────→│
     │                    handleEstablishedPacket()   │
     │                    ✓ Accept                    │
     │                    ✓ Forward                   │
     │                           │                    │
     │                           │  ServerHello       │
     │                  [Downlink thread]             │
     │                    ✓ Read from socket          │
     │                    ✓ Build TCP packet          │
     │                    ✓ Write to TUN              │
     │←──────────────────────────┤────────────────────┤
     │ ServerHello received ✓    │                    │
     │                           │                    │
     │                           │  Certificate       │
     │←──────────────────────────┤────────────────────┤
     │ Certificate received ✓    │                    │
     │                           │                    │
     │ CertificateVerify         │                    │
     ├──────────────────────────→│──────────────────→│
     │                           │                    │
     │ Finished                  │                    │
     ├──────────────────────────→│──────────────────→│
     │                           │                    │
     │                           │  Finished          │
     │←──────────────────────────┤────────────────────┤
     │                           │                    │
     │ ✓ TLS Handshake Complete                      │
     │ ✓ HTTPS Connection Ready                      │
     │                           │                    │
```

## Before vs After Fix

### BEFORE (Broken)
```
App → ClientHello → VPN
                      │
                      └─→ Forward to server ✓
                      
Server → ServerHello → VPN
                        │
                        ├─→ Packet has payload
                        ├─→ Seq != expectedSeq (TLS data)
                        └─→ ❌ SEND RST (INCORRECT!)
                        
App receives RST ❌
Connection dead ❌
HTTPS fails ❌
```

### AFTER (Fixed)
```
App → ClientHello → VPN
                      │
                      └─→ Forward to server ✓
                      
Server → ServerHello → VPN
                        │
                        ├─→ Packet has payload
                        ├─→ State == ESTABLISHED
                        ├─→ handleEstablishedPacket()
                        └─→ ✓ ACCEPT & FORWARD (CORRECT!)
                        
App receives ServerHello ✓
TLS handshake continues ✓
HTTPS works ✓
```

## Summary

The fix ensures that **ESTABLISHED state behaves like NetGuard**:
- Accept all packets
- Never reject based on sequence numbers
- Never send RST for expected protocol behavior
- Let TCP stream forwarding handle data naturally
- Only send RST when truly required (unknown flows, policy blocks)

