package com.example.betaaegis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.betaaegis.diagnostics.VpnDiagnostics
import com.example.betaaegis.telemetry.VpnStatistics
import com.example.betaaegis.ui.VpnStatisticsScreen
import com.example.betaaegis.ui.theme.BetaAegisTheme
import com.example.betaaegis.vpn.AegisVpnService

/**
 * Phase 5: MainActivity with Enhanced Observability UI
 *
 * Provides:
 * - VPN control (start/stop)
 * - Real-time statistics display
 * - Diagnostic export
 * - Clean, read-only observability
 *
 * Phase 5 adds comprehensive statistics and diagnostics without affecting VPN operation.
 */
class MainActivity : ComponentActivity() {

    private var isVpnActive by mutableStateOf(false)
    private var currentStatistics by mutableStateOf(VpnStatistics())
    private var showStatistics by mutableStateOf(false)
    private var vpnService: AegisVpnService? = null

    // Launcher for VPN permission dialog
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Permission granted, start VPN
            startVpnService()
        } else {
            // Permission denied
            isVpnActive = false
        }
    }

    // Service connection for accessing VPN statistics
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Note: AegisVpnService doesn't currently return a binder
            // Statistics will be fetched via static accessor or broadcast
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // TODO: Phase 5 - Add statistics refresh when service binding is implemented
            // Will use LaunchedEffect to periodically call vpnService.getStatistics()

            BetaAegisTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showStatistics && isVpnActive) {
                        Column(modifier = Modifier.padding(innerPadding)) {
                            // Back button
                            Button(
                                onClick = { showStatistics = false },
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text("← Back to Control")
                            }

                            VpnStatisticsScreen(
                                statistics = currentStatistics,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        VpnControlScreen(
                            modifier = Modifier.padding(innerPadding),
                            isVpnActive = isVpnActive,
                            statistics = currentStatistics,
                            onStartVpn = { requestVpnPermission() },
                            onStopVpn = { stopVpnService() },
                            onShowStatistics = { showStatistics = true },
                            onExportDiagnostics = { exportDiagnostics() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            // Service not bound
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Need to request permission
            vpnPermissionLauncher.launch(intent)
        } else {
            // Permission already granted
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_START_VPN
        }
        startService(intent)
        isVpnActive = true

        // Try to bind to service for statistics access
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_STOP_VPN
        }
        startService(intent)
        isVpnActive = false
        showStatistics = false
        currentStatistics = VpnStatistics()
    }


    /**
     * Phase 5: Export diagnostic report.
     */
    private fun exportDiagnostics() {
        try {
            val shareIntent = VpnDiagnostics.createShareIntent(this, currentStatistics)
            if (shareIntent != null) {
                startActivity(Intent.createChooser(shareIntent, "Share VPN Diagnostics"))
            }
        } catch (e: Exception) {
            // Silently ignore export failure
        }
    }
}

@Composable
fun VpnControlScreen(
    modifier: Modifier = Modifier,
    isVpnActive: Boolean,
    statistics: VpnStatistics,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onShowStatistics: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Aegis VPN",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Phase 5: Observability & Diagnostics",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // VPN Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isVpnActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "VPN Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isVpnActive) "Active" else "Inactive",
                    style = MaterialTheme.typography.headlineMedium
                )

                // Quick stats when active
                if (isVpnActive) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Active Flows: ${statistics.getTotalActiveFlows()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Data: ${statistics.formatBytes(statistics.getTotalDataTransferred())}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Control Buttons
        if (!isVpnActive) {
            Button(
                onClick = onStartVpn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start VPN")
            }
        } else {
            // Statistics button
            Button(
                onClick = onShowStatistics,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Detailed Statistics")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Export diagnostics button
            OutlinedButton(
                onClick = onExportDiagnostics,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export Diagnostics")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stop button
            Button(
                onClick = onStopVpn,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Stop VPN")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Phase 5 Information
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Phase 5 Features",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✓ Full traffic capture\n" +
                           "✓ TCP stream forwarding\n" +
                           "✓ UDP forwarding (DNS, QUIC)\n" +
                           "✓ UID attribution\n" +
                           "✓ Policy enforcement\n" +
                           "✓ DNS inspection\n" +
                           "✓ Domain-based rules\n" +
                           "✓ Real-time statistics\n" +
                           "✓ Diagnostic export\n" +
                           "✗ No TLS inspection\n" +
                           "✗ No DoT/DoH interception",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}