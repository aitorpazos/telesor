package dev.telesor.shizuku

import android.util.Log
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuHelper"

/**
 * Helper to check Shizuku availability and request permission.
 *
 * Shizuku is needed on the consumer device to access VirtualDeviceManager
 * (which requires @SystemApi CREATE_VIRTUAL_DEVICE permission).
 */
object ShizukuHelper {

    /** Check if Shizuku service is running and we have permission. */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    /** Check if Shizuku service is running (even if we don't have permission yet). */
    fun isServiceRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /** Request Shizuku permission. Call from an Activity context. */
    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    /**
     * Add a listener for permission result.
     */
    fun addPermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removePermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }
}
