package dev.remoty.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remoty.crypto.SessionCrypto
import dev.remoty.data.ConnectionState
import dev.remoty.data.DeviceRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    roleName: String,
    onPaired: () -> Unit,
    onBack: () -> Unit,
) {
    val role = try { DeviceRole.valueOf(roleName) } catch (_: Exception) { DeviceRole.PROVIDER }
    var connectionState by remember { mutableStateOf(ConnectionState.BLE_DISCOVERING) }
    val pairingCode = remember { SessionCrypto.generatePairingCode() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pair Devices") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (role) {
                DeviceRole.PROVIDER -> ProviderPairingContent(
                    pairingCode = pairingCode,
                    connectionState = connectionState,
                )
                DeviceRole.CONSUMER -> ConsumerPairingContent(
                    connectionState = connectionState,
                    onDeviceSelected = { /* TODO: initiate pairing */ },
                )
            }
        }
    }
}

@Composable
private fun ProviderPairingContent(
    pairingCode: String,
    connectionState: ConnectionState,
) {
    Spacer(Modifier.height(32.dp))

    Icon(
        imageVector = Icons.Outlined.Bluetooth,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary,
    )

    Spacer(Modifier.height(24.dp))

    Text(
        text = "Enter this code on the other device",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    // Big pairing code display
    Text(
        text = pairingCode.chunked(3).joinToString(" "),
        style = MaterialTheme.typography.displayMedium.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 8.sp,
        ),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )

    Spacer(Modifier.height(32.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Waiting for connection…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConsumerPairingContent(
    connectionState: ConnectionState,
    onDeviceSelected: (String) -> Unit,
) {
    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Scanning for nearby Remoty providers…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(24.dp))

    // Placeholder for discovered devices list
    Text(
        text = "Discovered devices will appear here",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
    )
}
