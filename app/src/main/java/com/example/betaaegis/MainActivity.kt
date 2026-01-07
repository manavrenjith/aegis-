package com.example.betaaegis

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
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
import com.example.betaaegis.ui.theme.BetaAegisTheme
import com.example.betaaegis.vpn.AegisVpnService

/**
 * Phase 3: MainActivity with VPN control UI
 *
 * Provides simple controls to:
 * - Request VPN permission
 * - Start the VPN service
 * - Stop the VPN service
 *
 * Phase 3 adds UDP forwarding and policy enforcement.
 */
class MainActivity : ComponentActivity() {

    private var isVpnActive by mutableStateOf(false)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BetaAegisTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VpnControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        isVpnActive = isVpnActive,
                        onStartVpn = { requestVpnPermission() },
                        onStopVpn = { stopVpnService() }
                    )
                }
            }
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
    }

    private fun stopVpnService() {
        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_STOP_VPN
        }
        startService(intent)
        isVpnActive = false
    }
}

@Composable
fun VpnControlScreen(
    modifier: Modifier = Modifier,
    isVpnActive: Boolean,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
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
            text = "Phase 3: Policy + UDP",
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

        // Phase 3 Information
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Phase 3 Scope",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✓ Capture all traffic\n" +
                           "✓ TCP stream forwarding\n" +
                           "✓ UDP forwarding (DNS, QUIC)\n" +
                           "✓ UID attribution\n" +
                           "✓ Policy enforcement\n" +
                           "✗ No domain-based rules yet\n" +
                           "✗ No TLS inspection",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}