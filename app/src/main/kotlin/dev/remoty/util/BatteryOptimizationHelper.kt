package dev.remoty.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

private const val TAG = "BatteryHelper"

/**
 * Utility to request battery optimization exemption.
 *
 * When Remoty is streaming in the background, Android may kill the
 * foreground service if the app is battery-optimized. Requesting
 * exemption ensures the streaming session stays alive.
 */
object BatteryOptimizationHelper {

    /**
     * Check if the app is already exempt from battery optimization.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Launch the system dialog to request battery optimization exemption.
     *
     * This uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS which shows
     * a simple yes/no dialog (no need to navigate through settings).
     *
     * Note: Google Play allows this for apps that perform background
     * data transfer / streaming.
     */
    fun requestExemption(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) {
            Log.i(TAG, "Already exempt from battery optimization")
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption", e)
            // Fallback: open battery optimization settings
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                Log.e(TAG, "Cannot open battery optimization settings")
            }
        }
    }
}
