package dev.remoty.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remoty.RemotyApp
import dev.remoty.data.PairedDevice
import dev.remoty.util.BatteryOptimizationHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferences = RemotyApp.instance.preferences

    val pairedDevices = remember { mutableStateListOf<PairedDevice>() }
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var deleteConfirmDevice by remember { mutableStateOf<PairedDevice?>(null) }

    LaunchedEffect(Unit) {
        pairedDevices.clear()
        pairedDevices.addAll(preferences.getPairedDevices())
        isBatteryOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Paired Devices Section ─────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Paired Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (pairedDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                Icons.Outlined.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "No paired devices yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(pairedDevices, key = { it.id }) { device ->
                PairedDeviceCard(
                    device = device,
                    onDelete = { deleteConfirmDevice = device },
                )
            }

            // ── Battery Optimization Section ───────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Battery",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Icons.Outlined.BatteryAlert,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = if (isBatteryOptimized) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unrestricted battery",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (isBatteryOptimized) {
                                    "App can run in background without restrictions"
                                } else {
                                    "Streaming may stop when app is in background"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = isBatteryOptimized,
                            onCheckedChange = {
                                if (!isBatteryOptimized) {
                                    BatteryOptimizationHelper.requestExemption(context)
                                }
                            },
                        )
                    }
                }
            }

            // ── About Section ──────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Remoty v0.1.0",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Share camera & NFC between Android devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Icons.Outlined.Security,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Security",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "All traffic is encrypted with AES-256-GCM.\n" +
                                        "Keys are derived via ECDH + pairing code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Delete confirmation dialog
    deleteConfirmDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { deleteConfirmDevice = null },
            title = { Text("Remove device?") },
            text = {
                Text("Remove \"${device.name}\" from paired devices? You'll need to pair again to connect.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            preferences.removePairedDevice(device.id)
                            pairedDevices.removeAll { it.id == device.id }
                        }
                        deleteConfirmDevice = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmDevice = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun PairedDeviceCard(
    device: PairedDevice,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val lastSeen = if (device.lastSeenTimestamp > 0) {
        "Last seen: ${dateFormat.format(Date(device.lastSeenTimestamp))}"
    } else {
        "Never connected"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Icons.Outlined.Devices,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = lastSeen,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "ID: ${device.id.take(8)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Remove device",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
