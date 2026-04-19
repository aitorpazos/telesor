package dev.remoty.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import dev.remoty.data.DeviceRole
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "BleDiscovery"

/**
 * Discovered Remoty device from BLE scan.
 */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val role: DeviceRole,
    val rssi: Int,
    val deviceId: String,
)

/**
 * BLE discovery manager — handles both advertising (so the other device finds us)
 * and scanning (so we find the other device).
 */
class BleDiscoveryManager(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /**
     * Start advertising this device as a Remoty [role].
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(role: DeviceRole, deviceId: String) {
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertiser not available")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Manufacturer data: MAGIC (4) + role (1) + deviceId first 8 chars
        val roleFlag = if (role == DeviceRole.PROVIDER) 0x01.toByte() else 0x02.toByte()
        val idBytes = deviceId.take(8).toByteArray(Charsets.UTF_8)
        val mfgData = BleConstants.ADVERTISEMENT_MAGIC + byteArrayOf(roleFlag) + idBytes

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .addManufacturerData(BleConstants.MANUFACTURER_ID, mfgData)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "Advertising started as $role")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }
        advertiseCallback = callback
        adv.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        advertiseCallback = null
    }

    /**
     * Scan for nearby Remoty devices. Emits discovered devices as a Flow.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val bleScanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            close()
            return@callbackFlow
        }
        scanner = bleScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = parseScanResult(result) ?: return
                trySend(device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                close()
            }
        }

        bleScanner.startScan(listOf(filter), settings, callback)

        awaitClose {
            bleScanner.stopScan(callback)
        }
    }

    private fun parseScanResult(result: ScanResult): DiscoveredDevice? {
        val mfgData = result.scanRecord
            ?.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID)
            ?: return null

        // Validate magic
        if (mfgData.size < 5) return null
        val magic = mfgData.copyOfRange(0, 4)
        if (!magic.contentEquals(BleConstants.ADVERTISEMENT_MAGIC)) return null

        val roleByte = mfgData[4]
        val role = when (roleByte) {
            0x01.toByte() -> DeviceRole.PROVIDER
            0x02.toByte() -> DeviceRole.CONSUMER
            else -> return null
        }

        val deviceId = if (mfgData.size > 5) {
            String(mfgData.copyOfRange(5, mfgData.size), Charsets.UTF_8)
        } else ""

        return DiscoveredDevice(
            address = result.device.address,
            name = result.scanRecord?.deviceName,
            role = role,
            rssi = result.rssi,
            deviceId = deviceId,
        )
    }
}
