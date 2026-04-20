package dev.telesor.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.telesor.TelesorApp
import dev.telesor.ble.BleDiscoveryManager
import dev.telesor.ble.BlePairingClient
import dev.telesor.ble.BlePairingServer
import dev.telesor.ble.DiscoveredDevice
import dev.telesor.ble.PairingResult
import dev.telesor.crypto.SessionCrypto
import dev.telesor.data.DeviceRole
import dev.telesor.net.ConnectionManager
import dev.telesor.net.NetworkUtils

private const val TAG = "PairingScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    roleName: String,
    connectionManager: ConnectionManager,
    onPaired: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val role = try { DeviceRole.valueOf(roleName) } catch (_: Exception) { DeviceRole.PROVIDER }

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
                .weight(1f)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (role) {
                DeviceRole.PROVIDER -> ProviderPairingContent(
                    context = context,
                    connectionManager = connectionManager,
                    onPaired = onPaired,
                )
                DeviceRole.CONSUMER -> ConsumerPairingContent(
                    context = context,
                    connectionManager = connectionManager,
                    onPaired = onPaired,
                )
            }
        }

        // Bottom hint
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Both devices must be on the same WiFi network.\nNo Bluetooth pre-pairing is needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ProviderPairingContent(
    context: Context,
    connectionManager: ConnectionManager,
    onPaired: () -> Unit,
) {
    val sessionCrypto = remember { SessionCrypto() }
    val pairingCode = remember { SessionCrypto.generatePairingCode() }
    var statusText by remember { mutableStateOf("Initializing...") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isPaired by remember { mutableStateOf(false) }

    val discoveryManager = remember { BleDiscoveryManager(context) }
    var pairingServer by remember { mutableStateOf<BlePairingServer?>(null) }

    // Get device ID and start BLE advertising + GATT server
    LaunchedEffect(Unit) {
        val deviceId = TelesorApp.instance.preferences.getDeviceId()
        val wifiIp = NetworkUtils.getLocalWifiIpAddress(context)

        if (wifiIp == null) {
            errorText = "No WiFi connection detected. Please connect to WiFi."
            statusText = "Error"
            return@LaunchedEffect
        }

        statusText = "Starting BLE advertising..."

        // Start advertising so consumer can find us
        discoveryManager.startAdvertising(DeviceRole.PROVIDER, deviceId)

        // Create and start GATT server
        // Port 0 = auto-assign; the actual port is returned by TelesorChannel.listen()
        // We pass port 0 here and the server will tell the consumer the real port
        // after the TCP server binds.
        // BUT: BlePairingServer needs the port upfront for CHAR_WIFI_INFO.
        // So we need to pre-bind a ServerSocket to get the port.
        val tempPort = try {
            val ss = java.net.ServerSocket(0)
            val p = ss.localPort
            ss.close()
            p
        } catch (e: Exception) {
            errorText = "Failed to allocate TCP port: ${e.message}"
            return@LaunchedEffect
        }

        val server = BlePairingServer(
            context = context,
            sessionCrypto = sessionCrypto,
            expectedPairingCode = pairingCode,
            deviceId = deviceId,
            wifiHost = wifiIp,
            wifiPort = tempPort,
            onPairingComplete = { result ->
                isPaired = true
                statusText = "Paired! Connecting via WiFi..."
                connectionManager.startAsProvider(
                    crypto = sessionCrypto,
                    port = result.wifiPort,
                )
                onPaired()
            },
            onPairingFailed = { reason ->
                errorText = "Pairing failed: $reason"
                statusText = "Failed"
            },
        )
        pairingServer = server
        server.start()
        statusText = "Waiting for connection..."
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            discoveryManager.stopAdvertising()
            pairingServer?.stop()
        }
    }

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

    if (errorText != null) {
        Text(
            text = errorText!!,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    } else if (!isPaired) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ConsumerPairingContent(
    context: Context,
    connectionManager: ConnectionManager,
    onPaired: () -> Unit,
) {
    val discoveryManager = remember { BleDiscoveryManager(context) }
    val discoveredDevices = remember { mutableStateListOf<DiscoveredDevice>() }
    var selectedDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var pairingCodeInput by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Scanning...") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isPairing by remember { mutableStateOf(false) }
    var pairingClient by remember { mutableStateOf<BlePairingClient?>(null) }

    val bluetoothManager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    val bluetoothAdapter: BluetoothAdapter? = remember { bluetoothManager.adapter }

    // Start BLE scan
    LaunchedEffect(Unit) {
        discoveryManager.scan().collect { device ->
            // Only show providers, deduplicate by address
            if (device.role == DeviceRole.PROVIDER) {
                val existing = discoveredDevices.indexOfFirst { it.address == device.address }
                if (existing >= 0) {
                    discoveredDevices[existing] = device
                } else {
                    discoveredDevices.add(device)
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            pairingClient?.stop()
        }
    }

    if (selectedDevice == null) {
        // Phase 1: Show discovered devices
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Scanning for nearby Telesor providers...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))

        if (discoveredDevices.isEmpty()) {
            Text(
                text = "Make sure the provider device is nearby\nwith Bluetooth enabled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = "Tap a device to connect:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(discoveredDevices.toList(), key = { it.address }) { device ->
                    DiscoveredDeviceCard(
                        device = device,
                        onClick = { selectedDevice = device },
                    )
                }
            }
        }
    } else {
        // Phase 2: Enter pairing code for selected device
        Spacer(Modifier.height(32.dp))

        Icon(
            imageVector = Icons.Outlined.BluetoothSearching,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Connecting to ${selectedDevice!!.name ?: selectedDevice!!.deviceId}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Enter the 6-digit code shown on the provider",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = pairingCodeInput,
            onValueChange = { value ->
                if (value.length <= 6 && value.all { it.isDigit() }) {
                    pairingCodeInput = value
                }
            },
            label = { Text("Pairing Code") },
            placeholder = { Text("000000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (pairingCodeInput.length == 6 && !isPairing) {
                        initiateConsumerPairing(
                            context = context,
                            bluetoothAdapter = bluetoothAdapter,
                            selectedDevice = selectedDevice!!,
                            pairingCode = pairingCodeInput,
                            connectionManager = connectionManager,
                            onStatusUpdate = { statusText = it },
                            onError = { errorText = it; isPairing = false },
                            onPairingStarted = { client -> pairingClient = client; isPairing = true },
                            onPaired = onPaired,
                        )
                    }
                },
            ),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(0.6f),
            enabled = !isPairing,
        )

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = {
                if (pairingCodeInput.length == 6 && !isPairing) {
                    initiateConsumerPairing(
                        context = context,
                        bluetoothAdapter = bluetoothAdapter,
                        selectedDevice = selectedDevice!!,
                        pairingCode = pairingCodeInput,
                        connectionManager = connectionManager,
                        onStatusUpdate = { statusText = it },
                        onError = { errorText = it; isPairing = false },
                        onPairingStarted = { client -> pairingClient = client; isPairing = true },
                        onPaired = onPaired,
                    )
                }
            },
            enabled = pairingCodeInput.length == 6 && !isPairing,
        ) {
            Text("Connect")
        }

        Spacer(Modifier.height(16.dp))

        if (errorText != null) {
            Text(
                text = errorText!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                errorText = null
                pairingCodeInput = ""
                selectedDevice = null
                pairingClient?.stop()
                pairingClient = null
            }) {
                Text("Try again")
            }
        } else if (isPairing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (!isPairing && errorText == null) {
            TextButton(onClick = {
                selectedDevice = null
                pairingCodeInput = ""
            }) {
                Text("Choose a different device")
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun initiateConsumerPairing(
    context: Context,
    bluetoothAdapter: BluetoothAdapter?,
    selectedDevice: DiscoveredDevice,
    pairingCode: String,
    connectionManager: ConnectionManager,
    onStatusUpdate: (String) -> Unit,
    onError: (String) -> Unit,
    onPairingStarted: (BlePairingClient) -> Unit,
    onPaired: () -> Unit,
) {
    if (bluetoothAdapter == null) {
        onError("Bluetooth not available")
        return
    }

    onStatusUpdate("Connecting via BLE...")

    val sessionCrypto = SessionCrypto()
    val deviceId = android.os.Build.MODEL // Fallback; ideally use preferences but this is sync

    val client = BlePairingClient(
        context = context,
        sessionCrypto = sessionCrypto,
        pairingCode = pairingCode,
        deviceId = deviceId,
        onPairingComplete = { result: PairingResult ->
            onStatusUpdate("Paired! Connecting via WiFi...")
            connectionManager.startAsConsumer(
                crypto = sessionCrypto,
                host = result.wifiHost,
                port = result.wifiPort,
            )
            onPaired()
        },
        onPairingFailed = { reason ->
            onError("Pairing failed: $reason")
        },
    )
    onPairingStarted(client)

    val bleDevice = bluetoothAdapter.getRemoteDevice(selectedDevice.address)
    client.connect(bleDevice)
}

@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredDevice,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Devices,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Telesor Device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "ID: ${device.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
