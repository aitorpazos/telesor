package dev.telesor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import dev.telesor.crypto.SessionCrypto

private const val TAG = "BlePairingClient"

/**
 * GATT client used by the **consumer** to complete the pairing handshake
 * with a provider's [BlePairingServer].
 *
 * Flow:
 * 1. Connect to the provider's GATT server
 * 2. Discover services
 * 3. Read provider's identity (device ID)
 * 4. Read provider's ECDH public key
 * 5. Write our pairing code + public key + device ID to the pairing characteristic
 * 6. Read WiFi connection info (IP:port) from provider
 * 7. Derive shared session key from ECDH + pairing code
 * 8. Report success with [PairingResult]
 */
class BlePairingClient(
    private val context: Context,
    private val sessionCrypto: SessionCrypto,
    private val pairingCode: String,
    private val deviceId: String,
    private val onPairingComplete: (PairingResult) -> Unit,
    private val onPairingFailed: (String) -> Unit,
) {
    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        private var remoteDeviceId: String? = null
        private var remotePubKey: String? = null
        private var wifiInfo: String? = null
        private var service: BluetoothGattService? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected, discovering services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected (status=$status)")
                if (remotePubKey == null) {
                    onPairingFailed("Connection lost during pairing")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onPairingFailed("Service discovery failed: $status")
                return
            }

            service = g.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                onPairingFailed("Telesor service not found on remote device")
                return
            }

            Log.i(TAG, "Services discovered, reading identity")
            val identityChar = service!!.getCharacteristic(BleConstants.CHAR_IDENTITY_UUID)
            g.readCharacteristic(identityChar)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharRead(g, characteristic.uuid, status, characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharRead(g, characteristic.uuid, status, value)
        }

        private fun handleCharRead(
            g: BluetoothGatt,
            uuid: java.util.UUID,
            status: Int,
            value: ByteArray?,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
                onPairingFailed("Read failed for $uuid (status=$status)")
                return
            }

            val text = String(value, Charsets.UTF_8)

            when (uuid) {
                BleConstants.CHAR_IDENTITY_UUID -> {
                    remoteDeviceId = text
                    Log.i(TAG, "Got remote device ID: $text, reading pubkey")
                    val pubkeyChar = service!!.getCharacteristic(BleConstants.CHAR_PUBKEY_UUID)
                    g.readCharacteristic(pubkeyChar)
                }

                BleConstants.CHAR_PUBKEY_UUID -> {
                    remotePubKey = text
                    Log.i(TAG, "Got remote pubkey, writing pairing data")
                    writePairingData(g)
                }

                BleConstants.CHAR_WIFI_INFO_UUID -> {
                    wifiInfo = text
                    Log.i(TAG, "Got WiFi info: $text")
                    completePairing()
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun writePairingData(g: BluetoothGatt) {
            val payload = "$pairingCode|${sessionCrypto.publicKeyBase64}|$deviceId"
            val pairingChar = service!!.getCharacteristic(BleConstants.CHAR_PAIRING_UUID)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    pairingChar,
                    payload.toByteArray(Charsets.UTF_8),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
            } else {
                @Suppress("DEPRECATION")
                pairingChar.value = payload.toByteArray(Charsets.UTF_8)
                @Suppress("DEPRECATION")
                g.writeCharacteristic(pairingChar)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == BleConstants.CHAR_PAIRING_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onPairingFailed("Pairing rejected by provider (wrong code?)")
                    return
                }
                Log.i(TAG, "Pairing write accepted, reading WiFi info")
                val wifiChar = service!!.getCharacteristic(BleConstants.CHAR_WIFI_INFO_UUID)
                g.readCharacteristic(wifiChar)
            }
        }

        private fun completePairing() {
            val rpk = remotePubKey
            val rid = remoteDeviceId
            val wifi = wifiInfo

            if (rpk == null || rid == null || wifi == null) {
                onPairingFailed("Incomplete pairing data")
                return
            }

            // Derive session key
            try {
                sessionCrypto.deriveSessionKey(rpk, pairingCode)
            } catch (e: Exception) {
                onPairingFailed("Key derivation failed: ${e.message}")
                return
            }

            val parts = wifi.split(":", limit = 2)
            if (parts.size != 2) {
                onPairingFailed("Invalid WiFi info: $wifi")
                return
            }

            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: run {
                onPairingFailed("Invalid port in WiFi info: $wifi")
                return
            }

            Log.i(TAG, "Pairing complete with $rid at $host:$port")

            // Clean up GATT connection — we're moving to WiFi now
            gatt?.disconnect()
            gatt?.close()
            gatt = null

            onPairingComplete(
                PairingResult(
                    remoteDeviceId = rid,
                    remotePublicKey = rpk,
                    wifiHost = host,
                    wifiPort = port,
                )
            )
        }
    }
}
