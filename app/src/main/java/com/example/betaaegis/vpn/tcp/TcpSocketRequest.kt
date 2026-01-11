package com.example.betaaegis.vpn.tcp

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture

data class TcpSocketRequest(
    val destIp: InetAddress,
    val destPort: Int,
    val timeoutMs: Int,
    val result: CompletableFuture<Socket>
)

