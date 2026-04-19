package dev.telesor.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.telesor.data.DeviceRole
import dev.telesor.util.PermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGateScreen(
    roleName: String,
    onAllGranted: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val role = try { DeviceRole.valueOf(roleName) } catch (_: Exception) { DeviceRole.PROVIDER }

    val requiredPermissions = when (role) {
        DeviceRole.PROVIDER -> PermissionHelper.PROVIDER_PERMISSIONS
        DeviceRole.CONSUMER -> PermissionHelper.CONSUMER_PERMISSIONS
    }

    var missingPermissions by remember {
        mutableStateOf(PermissionHelper.getMissingPermissions(context, requiredPermissions))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        missingPermissions = PermissionHelper.getMissingPermissions(context, requiredPermissions)
        if (missingPermissions.isEmpty()) {
            onAllGranted()
        }
    }

    // Auto-proceed if already granted
    LaunchedEffect(missingPermissions) {
        if (missingPermissions.isEmpty()) {
            onAllGranted()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Permissions") },
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
        ) {
            Text(
                text = "Telesor needs the following permissions to work as a ${role.name.lowercase()}:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            // Permission groups
            PermissionRow(
                label = "Bluetooth",
                icon = Icons.Outlined.Bluetooth,
                granted = PermissionHelper.allGranted(context, PermissionHelper.BLE_PERMISSIONS),
                description = "Discover and pair with nearby devices",
            )

            PermissionRow(
                label = "Location",
                icon = Icons.Outlined.LocationOn,
                granted = PermissionHelper.allGranted(context, PermissionHelper.LOCATION_PERMISSIONS),
                description = "Required for BLE scanning",
            )

            if (role == DeviceRole.PROVIDER) {
                PermissionRow(
                    label = "Camera",
                    icon = Icons.Outlined.CameraAlt,
                    granted = PermissionHelper.allGranted(context, PermissionHelper.CAMERA_PERMISSIONS),
                    description = "Stream camera to remote device",
                )

                PermissionRow(
                    label = "NFC",
                    icon = Icons.Outlined.Nfc,
                    granted = PermissionHelper.allGranted(context, PermissionHelper.NFC_PERMISSIONS),
                    description = "Read NFC tags for relay",
                )
            }

            if (PermissionHelper.NOTIFICATION_PERMISSIONS.isNotEmpty()) {
                PermissionRow(
                    label = "Notifications",
                    icon = Icons.Outlined.Notifications,
                    granted = PermissionHelper.allGranted(context, PermissionHelper.NOTIFICATION_PERMISSIONS),
                    description = "Show streaming status notification",
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    launcher.launch(requiredPermissions)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = missingPermissions.isNotEmpty(),
            ) {
                Text(
                    if (missingPermissions.isEmpty()) "All granted ✓"
                    else "Grant Permissions"
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    icon: ImageVector,
    granted: Boolean,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (granted) Icons.Outlined.CheckCircle
            else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (granted) "Granted" else "Not granted",
            modifier = Modifier.size(24.dp),
            tint = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
        )
    }
}
