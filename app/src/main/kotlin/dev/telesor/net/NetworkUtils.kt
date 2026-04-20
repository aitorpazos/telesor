package dev.telesor.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "NetworkUtils"

/**
 * Network utilities for discovering the local WiFi IP address.
 */
object NetworkUtils {

    /**
     * Get the device's local WiFi IPv4 address.
     *
     * Tries ConnectivityManager first (modern API), then falls back
     * to iterating NetworkInterfaces (works on older devices).
     *
     * Returns null if no WiFi address is found.
     */
    fun getLocalWifiIpAddress(context: Context): String? {
        // Try modern API first
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return fallbackIp()
            val caps = cm.getNetworkCapabilities(network) ?: return fallbackIp()

            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                Log.w(TAG, "Active network is not WiFi, falling back to interface scan")
                return fallbackIp()
            }

            val linkProps = cm.getLinkProperties(network) ?: return fallbackIp()
            for (addr in linkProps.linkAddresses) {
                val ip = addr.address
                if (ip is Inet4Address && !ip.isLoopbackAddress) {
                    return ip.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager lookup failed", e)
        }

        return fallbackIp()
    }

    /**
     * Fallback: iterate all network interfaces looking for a non-loopback IPv4 address
     * on a WiFi-like interface (wlan0, wlan1, etc.).
     */
    private fun fallbackIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                // Prefer wlan interfaces but accept any non-loopback
                val name = intf.name.lowercase()
                if (!intf.isUp || intf.isLoopback) continue

                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        // Prefer wlan
                        if (name.startsWith("wlan") || name.startsWith("wifi")) {
                            return addr.hostAddress
                        }
                    }
                }
            }

            // Second pass: accept any non-loopback IPv4
            val interfaces2 = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces2) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }
}
