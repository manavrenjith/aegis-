package com.example.betaaegis.vpn.tcp

import android.os.Handler
import android.os.Looper
import com.example.betaaegis.vpn.AegisVpnService
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture

/**
 * Main-Thread TCP Socket Request Queue
 *
 * PURPOSE:
 * Ensures all TCP socket creation, protection, and connection
 * execute on the VpnService MAIN THREAD (Binder thread).
 *
 * WHY THIS IS REQUIRED:
 * - VpnService.protect() authorization requires Binder context
 * - Calling protect() from worker threads is undefined behavior
 * - Android verifies service ownership + thread context
 * - Only the main thread satisfies all authorization requirements
 *
 * ARCHITECTURE:
 * - Worker threads enqueue socket requests
 * - Requests are posted to Handler(Looper.getMainLooper())
 * - Socket creation + protect() + connect() run on main thread
 * - Worker threads block synchronously waiting for result
 * - No retries, no delays, no timing hacks
 *
 * This is the exact pattern used by NetGuard-class VPNs.
 */
class TcpSocketRequestQueue(
    private val vpnService: AegisVpnService
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Request a TCP socket from the main thread.
     *
     * This method:
     * - Posts socket creation to main thread
     * - Blocks calling thread until completion
     * - Returns connected, protected socket
     * - Throws IOException on any failure
     *
     * CRITICAL: All three operations execute atomically on main thread:
     * 1. Socket()
     * 2. protect(socket)
     * 3. socket.connect()
     *
     * @param destIp Destination IP address
     * @param destPort Destination port
     * @param timeoutMs Connect timeout in milliseconds
     * @return Connected and protected Socket
     * @throws IOException if VPN not running or protect() fails
     */
    fun requestSocket(
        destIp: InetAddress,
        destPort: Int,
        timeoutMs: Int
    ): Socket {
        val future = CompletableFuture<Socket>()

        mainHandler.post {
            var socket: Socket? = null
            try {
                // Verify VPN is running
                if (!vpnService.isRunning()) {
                    throw IOException("VPN service not running")
                }

                // Create socket
                socket = Socket()

                // Protect socket (MUST be on main thread)
                if (!vpnService.protect(socket)) {
                    throw IOException("VpnService.protect() failed")
                }

                // Connect socket
                socket.connect(
                    InetSocketAddress(destIp, destPort),
                    timeoutMs
                )

                future.complete(socket)
            } catch (e: Exception) {
                socket?.close()
                future.completeExceptionally(e)
            }
        }

        // Worker thread blocks here
        return future.get()
    }
}

