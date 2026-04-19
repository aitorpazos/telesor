package dev.remoty.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions required by Remoty, grouped by feature.
 *
 * All permissions are declared in AndroidManifest.xml.
 * This helper identifies which ones need runtime grants.
 */
object PermissionHelper {

    /** BLE discovery & pairing permissions. */
    val BLE_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    )

    /** Location (needed for BLE scanning on some OEMs). */
    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** Camera (provider role). */
    val CAMERA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
    )

    /** NFC (provider role). */
    val NFC_PERMISSIONS = arrayOf(
        Manifest.permission.NFC,
    )

    /** Notifications (foreground service notification). */
    val NOTIFICATION_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    /**
     * All permissions needed for the provider role.
     */
    val PROVIDER_PERMISSIONS: Array<String>
        get() = BLE_PERMISSIONS +
                LOCATION_PERMISSIONS +
                CAMERA_PERMISSIONS +
                NFC_PERMISSIONS +
                NOTIFICATION_PERMISSIONS

    /**
     * All permissions needed for the consumer role.
     */
    val CONSUMER_PERMISSIONS: Array<String>
        get() = BLE_PERMISSIONS +
                LOCATION_PERMISSIONS +
                NOTIFICATION_PERMISSIONS

    /**
     * Check which permissions from the given list are not yet granted.
     */
    fun getMissingPermissions(context: Context, permissions: Array<String>): List<String> {
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if all permissions in the given list are granted.
     */
    fun allGranted(context: Context, permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
