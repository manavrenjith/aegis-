package com.example.betaaegis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.betaaegis.telemetry.VpnStatistics

/**
 * Phase 5: VPN Statistics Display Screen
 *
 * PURPOSE:
 * - Display VPN metrics in user-friendly format
 * - Show real-time statistics
 * - Read-only view (no actions)
 *
 * SAFETY:
 * - UI never affects VPN operation
 * - All data is read-only snapshots
 * - Graceful handling of null/missing data
 */
@Composable
fun VpnStatisticsScreen(
    statistics: VpnStatistics,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "VPN Statistics",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Overall Traffic Card
        StatisticsCard(title = "Overall Traffic") {
            StatRow("Total Packets", statistics.totalPackets.toString())
            StatRow("Total Data", statistics.formatBytes(statistics.getTotalDataTransferred()))
            StatRow("Active Flows", statistics.getTotalActiveFlows().toString())
            StatRow(
                "Last Activity",
                formatLastActivity(statistics.lastPacketTimestamp)
            )
        }

        // TCP Statistics Card
        StatisticsCard(title = "TCP Statistics") {
            StatRow("Active Flows", statistics.tcpActiveFlows.toString())
            StatRow("Total Created", statistics.tcpTotalFlowsCreated.toString())
            StatRow("Total Closed", statistics.tcpTotalFlowsClosed.toString())
            StatRow("Uplink", statistics.formatBytes(statistics.tcpBytesUplink))
            StatRow("Downlink", statistics.formatBytes(statistics.tcpBytesDownlink))
        }

        // UDP Statistics Card
        StatisticsCard(title = "UDP Statistics") {
            StatRow("Active Flows", statistics.udpActiveFlows.toString())
            StatRow("Total Created", statistics.udpTotalFlowsCreated.toString())
            StatRow("Total Closed", statistics.udpTotalFlowsClosed.toString())
            StatRow("Total Blocked", statistics.udpTotalFlowsBlocked.toString())
            StatRow("Uplink", statistics.formatBytes(statistics.udpBytesUplink))
            StatRow("Downlink", statistics.formatBytes(statistics.udpBytesDownlink))
        }

        // DNS Statistics Card
        StatisticsCard(title = "DNS & Domain Statistics") {
            StatRow("Cached Domains", statistics.dnsCacheSize.toString())
            StatRow("Queries Observed", statistics.dnsQueriesObserved.toString())
            StatRow("Responses Observed", statistics.dnsResponsesObserved.toString())
        }

        // Policy Statistics Card
        StatisticsCard(title = "Policy Statistics") {
            StatRow("Flows Allowed", statistics.totalFlowsAllowed.toString())
            StatRow("Flows Blocked", statistics.totalFlowsBlocked.toString())
        }
    }
}

@Composable
private fun StatisticsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatLastActivity(timestamp: Long): String {
    if (timestamp == 0L) return "Never"

    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 1000 -> "Just now"
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}

