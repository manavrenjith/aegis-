package com.example.betaaegis.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.betaaegis.telemetry.VpnStatistics
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Phase 5: VPN Diagnostics Export
 *
 * PURPOSE:
 * - Export VPN statistics for debugging
 * - Generate diagnostic reports
 * - Support troubleshooting
 *
 * SAFETY:
 * - Read-only operations
 * - Never affects VPN operation
 * - No sensitive data included
 * - User-initiated only
 */
object VpnDiagnostics {

    /**
     * Generate diagnostic report as text.
     *
     * @param stats Current VPN statistics
     * @return Formatted diagnostic text
     */
    fun generateReport(stats: VpnStatistics): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date())

        return buildString {
            appendLine("=== Aegis VPN Diagnostic Report ===")
            appendLine("Generated: $timestamp")
            appendLine()

            appendLine("--- Overall Traffic ---")
            appendLine("Total Packets: ${stats.totalPackets}")
            appendLine("Total Bytes: ${stats.formatBytes(stats.totalBytes)}")
            appendLine("Total Data: ${stats.formatBytes(stats.getTotalDataTransferred())}")
            appendLine("Last Packet: ${formatTimestamp(stats.lastPacketTimestamp)}")
            appendLine()

            appendLine("--- TCP Statistics ---")
            appendLine("Active Flows: ${stats.tcpActiveFlows}")
            appendLine("Total Created: ${stats.tcpTotalFlowsCreated}")
            appendLine("Total Closed: ${stats.tcpTotalFlowsClosed}")
            appendLine("Bytes Uplink: ${stats.formatBytes(stats.tcpBytesUplink)}")
            appendLine("Bytes Downlink: ${stats.formatBytes(stats.tcpBytesDownlink)}")
            appendLine()

            appendLine("--- UDP Statistics ---")
            appendLine("Active Flows: ${stats.udpActiveFlows}")
            appendLine("Total Created: ${stats.udpTotalFlowsCreated}")
            appendLine("Total Closed: ${stats.udpTotalFlowsClosed}")
            appendLine("Total Blocked: ${stats.udpTotalFlowsBlocked}")
            appendLine("Bytes Uplink: ${stats.formatBytes(stats.udpBytesUplink)}")
            appendLine("Bytes Downlink: ${stats.formatBytes(stats.udpBytesDownlink)}")
            appendLine()

            appendLine("--- DNS/Domain Statistics ---")
            appendLine("Cache Size: ${stats.dnsCacheSize} domains")
            appendLine("Queries Observed: ${stats.dnsQueriesObserved}")
            appendLine("Responses Observed: ${stats.dnsResponsesObserved}")
            appendLine()

            appendLine("--- Policy Statistics ---")
            appendLine("Total Flows Blocked: ${stats.totalFlowsBlocked}")
            appendLine("Total Flows Allowed: ${stats.totalFlowsAllowed}")
            appendLine()

            appendLine("=== End of Report ===")
        }
    }

    /**
     * Generate JSON diagnostic report.
     *
     * @param stats Current VPN statistics
     * @return JSON string
     */
    fun generateJsonReport(stats: VpnStatistics): String {
        return buildString {
            appendLine("{")
            appendLine("  \"timestamp\": ${System.currentTimeMillis()},")
            appendLine("  \"overall\": {")
            appendLine("    \"totalPackets\": ${stats.totalPackets},")
            appendLine("    \"totalBytes\": ${stats.totalBytes},")
            appendLine("    \"lastPacketTimestamp\": ${stats.lastPacketTimestamp}")
            appendLine("  },")
            appendLine("  \"tcp\": {")
            appendLine("    \"activeFlows\": ${stats.tcpActiveFlows},")
            appendLine("    \"totalFlowsCreated\": ${stats.tcpTotalFlowsCreated},")
            appendLine("    \"totalFlowsClosed\": ${stats.tcpTotalFlowsClosed},")
            appendLine("    \"bytesUplink\": ${stats.tcpBytesUplink},")
            appendLine("    \"bytesDownlink\": ${stats.tcpBytesDownlink}")
            appendLine("  },")
            appendLine("  \"udp\": {")
            appendLine("    \"activeFlows\": ${stats.udpActiveFlows},")
            appendLine("    \"totalFlowsCreated\": ${stats.udpTotalFlowsCreated},")
            appendLine("    \"totalFlowsClosed\": ${stats.udpTotalFlowsClosed},")
            appendLine("    \"totalFlowsBlocked\": ${stats.udpTotalFlowsBlocked},")
            appendLine("    \"bytesUplink\": ${stats.udpBytesUplink},")
            appendLine("    \"bytesDownlink\": ${stats.udpBytesDownlink}")
            appendLine("  },")
            appendLine("  \"dns\": {")
            appendLine("    \"cacheSize\": ${stats.dnsCacheSize},")
            appendLine("    \"queriesObserved\": ${stats.dnsQueriesObserved},")
            appendLine("    \"responsesObserved\": ${stats.dnsResponsesObserved}")
            appendLine("  },")
            appendLine("  \"policy\": {")
            appendLine("    \"totalFlowsBlocked\": ${stats.totalFlowsBlocked},")
            appendLine("    \"totalFlowsAllowed\": ${stats.totalFlowsAllowed}")
            appendLine("  }")
            appendLine("}")
        }
    }

    /**
     * Create shareable diagnostic file.
     *
     * @param context Android context
     * @param stats Current VPN statistics
     * @return Share Intent
     */
    fun createShareIntent(context: Context, stats: VpnStatistics): Intent? {
        return try {
            val report = generateReport(stats)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date())

            val fileName = "aegis_vpn_diagnostics_$timestamp.txt"
            val file = File(context.cacheDir, fileName)

            file.writeText(report)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Aegis VPN Diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Format timestamp as human-readable string.
     */
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "Never"

        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 1000 -> "Just now"
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
        }
    }
}

