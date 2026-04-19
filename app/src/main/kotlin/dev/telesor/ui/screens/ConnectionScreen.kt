package dev.telesor.ui.screens

import android.view.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.SignalWifi4Bar
import androidx.compose.material.icons.outlined.SignalWifiOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.telesor.camera.CameraPreviewView
import dev.telesor.camera.CameraSessionManager
import dev.telesor.camera.CameraSessionState
import dev.telesor.data.ConnectionState
import dev.telesor.data.DeviceRole
import dev.telesor.nfc.NfcSessionManager
import dev.telesor.nfc.NfcSessionState

@Composable
fun ConnectionScreen(
    roleName: String,
    latencyMs: Long = -1L,
    connectionState: ConnectionState = ConnectionState.CONNECTED,
    onDisconnect: () -> Unit,
    cameraSessionManager: CameraSessionManager? = null,
    onStartCamera: ((useFront: Boolean) -> Unit)? = null,
    onStopCamera: (() -> Unit)? = null,
    onSurfaceAvailable: ((Surface) -> Unit)? = null,
) {
    val role = try { DeviceRole.valueOf(roleName) } catch (_: Exception) { DeviceRole.PROVIDER }

    val cameraState by cameraSessionManager?.state?.collectAsState()
        ?: remember { mutableStateOf(CameraSessionState.IDLE) }
    val streamInfo by cameraSessionManager?.streamInfo?.collectAsState()
        ?: remember { mutableStateOf(null) }

    val isStreaming = cameraState == CameraSessionState.STREAMING
    var useFrontCamera by remember { mutableStateOf(false) }

    val isReconnecting = connectionState == ConnectionState.UPGRADING_TO_WIFI
    val isDisconnected = connectionState == ConnectionState.DISCONNECTED
    val isError = connectionState == ConnectionState.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Status header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = if (role == DeviceRole.PROVIDER) "Providing" else "Receiving",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusText = when {
                        isReconnecting -> "Reconnecting…"
                        isDisconnected -> "Disconnected"
                        isError -> "Connection error"
                        latencyMs >= 0 -> "Connected · ${latencyMs}ms"
                        else -> "Connected via WiFi"
                    }
                    val statusColor = when {
                        isReconnecting -> MaterialTheme.colorScheme.tertiary
                        isDisconnected || isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    }

                    if (isReconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = statusColor,
                        )
                        Spacer(Modifier.width(6.dp))
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                    )
                }
            }
            Icon(
                imageVector = when {
                    isReconnecting || isDisconnected || isError -> Icons.Outlined.SignalWifiOff
                    else -> Icons.Outlined.SignalWifi4Bar
                },
                contentDescription = "Connection status",
                modifier = Modifier.size(32.dp),
                tint = when {
                    isReconnecting -> MaterialTheme.colorScheme.tertiary
                    isDisconnected || isError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                },
            )
        }

        Spacer(Modifier.height(24.dp))

        // Camera preview (consumer sees decoded frames, provider sees local viewfinder)
        AnimatedVisibility(
            visible = isStreaming || role == DeviceRole.CONSUMER,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(16.dp)),
            ) {
                CameraPreviewView(
                    onSurfaceAvailable = { surface ->
                        onSurfaceAvailable?.invoke(surface)
                    },
                    onSurfaceDestroyed = {},
                )

                // Stream info overlay
                streamInfo?.let { info ->
                    Text(
                        text = "${info.width}×${info.height} · ${info.fps}fps",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                // Recording indicator
                if (isStreaming) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.Red,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Camera controls
        CameraControlCard(
            isStreaming = isStreaming,
            cameraState = cameraState,
            role = role,
            useFrontCamera = useFrontCamera,
            isConnected = !isReconnecting && !isDisconnected && !isError,
            onToggleCamera = {
                if (isStreaming) {
                    onStopCamera?.invoke()
                } else {
                    onStartCamera?.invoke(useFrontCamera)
                }
            },
            onSwitchFacing = {
                useFrontCamera = !useFrontCamera
                if (isStreaming) {
                    onStopCamera?.invoke()
                    onStartCamera?.invoke(useFrontCamera)
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        // NFC status card
        FeatureStatusCard(
            title = "NFC",
            description = if (role == DeviceRole.PROVIDER) "Waiting for tag…" else "Relay ready",
            icon = Icons.Outlined.Nfc,
            isActive = false,
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                onStopCamera?.invoke()
                onDisconnect()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Disconnect")
        }
    }
}

@Composable
private fun CameraControlCard(
    isStreaming: Boolean,
    cameraState: CameraSessionState,
    role: DeviceRole,
    useFrontCamera: Boolean,
    isConnected: Boolean,
    onToggleCamera: () -> Unit,
    onSwitchFacing: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isStreaming) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CameraAlt,
                    contentDescription = "Camera",
                    modifier = Modifier.size(32.dp),
                    tint = if (isStreaming) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Camera",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when (cameraState) {
                            CameraSessionState.IDLE ->
                                if (role == DeviceRole.PROVIDER) "Ready to stream" else "Waiting for stream"
                            CameraSessionState.STARTING -> "Starting…"
                            CameraSessionState.STREAMING ->
                                if (role == DeviceRole.PROVIDER) "Streaming to remote" else "Virtual camera active"
                            CameraSessionState.STOPPING -> "Stopping…"
                            CameraSessionState.ERROR -> "Error occurred"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onToggleCamera,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected &&
                            cameraState != CameraSessionState.STARTING &&
                            cameraState != CameraSessionState.STOPPING,
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Outlined.Stop else Icons.Outlined.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isStreaming) "Stop" else "Start Camera")
                }

                if (role == DeviceRole.PROVIDER) {
                    IconButton(onClick = onSwitchFacing) {
                        Icon(
                            imageVector = Icons.Outlined.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeatureStatusCard(
    title: String,
    description: String,
    icon: ImageVector,
    isActive: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(32.dp),
                tint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
