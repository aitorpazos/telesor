package dev.telesor.ui.screens

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.telesor.ble.BleDiscoveryManager
import dev.telesor.ble.BlePairingClient
import dev.telesor.ble.BlePairingServer
import dev.telesor.ble.DiscoveredDevice
import dev.telesor.ble.PairingResult
import dev.telesor.crypto.SessionCrypto
import dev.telesor.data.ConnectionState
import dev.telesor.data.DeviceRole
import dev.telesor.net.ConnectionManager
import dev.telesor.net.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val PROVIDER_TCP_PORT = 19850

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    roleName: String,
    connectionManager: ConnectionManager,
    onPaired: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val role = try { DeviceRole.valueOf(roleName) } catch (_: Exception) { DeviceRole.PROVIDER }

    // Shared crypto for this pairing session
    val sessionCrypto = remember { SessionCrypto() }

    // BLE discovery manager
    val discoveryManager = remember { BleDiscoveryManager(context) }

    // Pairing code — provider generates it, consumer enters it
    val pairingCode = remember { SessionCrypto.generatePairingCode() }

    // State
    var pairingStatus by remember { mutableStateOf<PairingStatus>(PairingStatus.Idle) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Provider-specific state
    var pairingServer by remember { mutableStateOf<BlePairingServer?>(null) }

    // Consumer-specific state
    val discoveredDevices = remember { mutableStateListOf<DiscoveredDevice>() }
    var selectedDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var enteredCode by remember { mutableStateOf("") }
    var pairingClient by remember { mutableStateOf<BlePairingClient?>(null) }

    // Device ID for this device
    var deviceId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        deviceId = dev.telesor.TelesorApp.instance.preferences.getDeviceId()
    }

    // Navigate to ConnectionScreen only when TCP connection is actually established.
    // This prevents the race condition where onPaired() fires before the TCP
    // server/client has finished connecting.
    val connState by connectionManager.connectionState.collectAsState()
    var blePairingDone by remember { mutableStateOf(false) }

    LaunchedEffect(connState, blePairingDone) {
        if (blePairingDone && connState == ConnectionState.CONNECTED) {
            // TCP is up — safe to tear down the GATT server now
            pairingServer?.stop()
            pairingServer = null
            onPaired()
        }
        if (blePairingDone && connState == ConnectionState.ERROR) {
            errorMessage = "WiFi connection failed. Make sure both devices are on the same network."
            pairingStatus = PairingStatus.Error
            blePairingDone = false
            // Clean up GATT server on error too
            pairingServer?.stop()
            pairingServer = null
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            discoveryManager.stopAdvertising()
            pairingServer?.stop()
            pairingClient?.stop()
        }
    }

    // --- Provider: start advertising + GATT server ---
    if (role == DeviceRole.PROVIDER && deviceId.isNotEmpty()) {
        LaunchedEffect(deviceId) {
            val wifiIp = NetworkUtils.getLocalWifiIpAddress(context)
            if (wifiIp == null) {
                errorMessage = "No WiFi connection detected"
                pairingStatus = PairingStatus.Error
                return@LaunchedEffect
            }

            // Start BLE advertising
            discoveryManager.startAdvertising(DeviceRole.PROVIDER, deviceId)

            // Start GATT server
            val server = BlePairingServer(
                context = context,
                sessionCrypto = sessionCrypto,
                expectedPairingCode = pairingCode,
                deviceId = deviceId,
                wifiHost = wifiIp,
                wifiPort = PROVIDER_TCP_PORT,
                onPairingComplete = { result ->
                    scope.launch(Dispatchers.Main) {
                        pairingStatus = PairingStatus.Success
                        discoveryManager.stopAdvertising()
                        // DO NOT stop the GATT server here! The consumer still
                        // needs to read CHAR_WIFI_INFO after the pairing write.
                        // The server will be cleaned up in DisposableEffect or
                        // after TCP connection is established.

                        // Start TCP server as provider — navigation happens
                        // when connectionState reaches CONNECTED
                        connectionManager.startAsProvider(
                            crypto = sessionCrypto,
                            port = PROVIDER_TCP_PORT,
                        )
                        blePairingDone = true
                    }
                },
                onPairingFailed = { reason ->
                    scope.launch(Dispatchers.Main) {
                        errorMessage = reason
                        pairingStatus = PairingStatus.Error
                    }
                },
            )
            server.start()
            pairingServer = server
            pairingStatus = PairingStatus.WaitingForPeer
        }
    }

    // --- Consumer: start BLE scan ---
    if (role == DeviceRole.CONSUMER && deviceId.isNotEmpty()) {
        LaunchedEffect(deviceId) {
            pairingStatus = PairingStatus.Scanning
            discoveryManager.scan().collect { device ->
                // Only show providers, deduplicate by address
                if (device.role == DeviceRole.PROVIDER &&
                    discoveredDevices.none { it.address == device.address }
                ) {
                    discoveredDevices.add(device)
                }
            }
        }
    }

    // --- UI ---
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pair Devices") },
            navigationIcon = {
                IconButton(onClick = {
                    discoveryManager.stopAdvertising()
                    pairingServer?.stop()
                    pairingClient?.stop()
                    onBack()
                }) {
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
            // Error banner
            errorMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            when (role) {
                DeviceRole.PROVIDER -> ProviderPairingContent(
                    pairingCode = pairingCode,
                    pairingStatus = pairingStatus,
                )
                DeviceRole.CONSUMER -> ConsumerPairingContent(
                    discoveredDevices = discoveredDevices,
                    selectedDevice = selectedDevice,
                    enteredCode = enteredCode,
                    pairingStatus = pairingStatus,
                    onCodeChanged = { enteredCode = it },
                    onDeviceSelected = { device ->
                        selectedDevice = device
                    },
                    onSubmitCode = {
                        if (enteredCode.length == 6 && selectedDevice != null) {
                            pairingStatus = PairingStatus.Pairing

                            val bluetoothManager = context.getSystemService(
                                Context.BLUETOOTH_SERVICE
                            ) as BluetoothManager
                            val adapter = bluetoothManager.adapter
                            val bleDevice: BluetoothDevice = adapter.getRemoteDevice(
                                selectedDevice!!.address
                            )

                            val client = BlePairingClient(
                                context = context,
                                sessionCrypto = sessionCrypto,
                                pairingCode = enteredCode,
                                deviceId = deviceId,
                                onPairingComplete = { result: PairingResult ->
                                    scope.launch(Dispatchers.Main) {
                                        pairingStatus = PairingStatus.Success
                                        pairingClient?.stop()
                                        // Connect to provider's TCP server — navigation
                                        // happens when connectionState reaches CONNECTED
                                        connectionManager.startAsConsumer(
                                            crypto = sessionCrypto,
                                            host = result.wifiHost,
                                            port = result.wifiPort,
                                        )
                                        blePairingDone = true
                                    }
                                },
                                onPairingFailed = { reason ->
                                    scope.launch(Dispatchers.Main) {
                                        errorMessage = reason
                                        pairingStatus = PairingStatus.Error
                                    }
                                },
                            )
                            client.connect(bleDevice)
                            pairingClient = client
                        }
                    },
                )
            }

            Spacer(Modifier.weight(1f))

            // Hint at bottom
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Both devices must be on the same WiFi network. " +
                                "No Bluetooth pre-pairing is needed \u2014 " +
                                "Telesor uses BLE only for discovery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Internal pairing progress state. */
private sealed class PairingStatus {
    data object Idle : PairingStatus()
    data object Scanning : PairingStatus()
    data object WaitingForPeer : PairingStatus()
    data object Pairing : PairingStatus()
    data object Success : PairingStatus()
    data object Error : PairingStatus()
}

@Composable
private fun ProviderPairingContent(
    pairingCode: String,
    pairingStatus: PairingStatus,
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

    when (pairingStatus) {
        is PairingStatus.WaitingForPeer -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Advertising via BLE\u2026 waiting for consumer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is PairingStatus.Success -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Paired! Waiting for WiFi connection\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is PairingStatus.Error -> {
            Text(
                text = "Pairing failed. Go back and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Initializing\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConsumerPairingContent(
    discoveredDevices: List<DiscoveredDevice>,
    selectedDevice: DiscoveredDevice?,
    enteredCode: String,
    pairingStatus: PairingStatus,
    onCodeChanged: (String) -> Unit,
    onDeviceSelected: (DiscoveredDevice) -> Unit,
    onSubmitCode: () -> Unit,
) {
    if (pairingStatus is PairingStatus.Success) {
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Paired! Connecting via WiFi\u2026",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        return
    }

    if (pairingStatus is PairingStatus.Pairing) {
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Pairing via BLE\u2026",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // Step 1: select a provider device
    if (selectedDevice == null) {
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Scanning for nearby Telesor providers\u2026",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))

        if (discoveredDevices.isEmpty()) {
            Text(
                text = "No devices found yet. Make sure the provider\u2019s screen is open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(discoveredDevices, key = { it.address }) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Devices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = device.name ?: "Telesor Provider",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "ID: ${device.deviceId} \u00b7 RSSI: ${device.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Step 2: enter pairing code
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Selected: ${selectedDevice.name ?: "Telesor Provider"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Enter the 6-digit code shown on the provider",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = enteredCode,
            onValueChange = { value ->
                // Only allow digits, max 6
                val filtered = value.filter { it.isDigit() }.take(6)
                onCodeChanged(filtered)
                if (filtered.length == 6) {
                    onSubmitCode()
                }
            },
            label = { Text("Pairing Code") },
            placeholder = { Text("000 000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSubmitCode() },
            ),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (pairingStatus is PairingStatus.Error) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Pairing failed. Check the code and try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
