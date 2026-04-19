package dev.telesor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.util.Log
import dev.telesor.crypto.SessionCrypto

private const val TAG = "BlePairing"

/**
 * Result of a successful BLE pairing handshake.
 */
data class PairingResult(
    val remoteDeviceId: String,
    val remotePublicKey: String,
    val wifiHost: String,
    val wifiPort: Int,
)

/**
 * GATT server that handles the pairing handshake.
 *
 * The provider side:
 * 1. Starts GATT server with pairing characteristics
 * 2. Consumer connects and writes pairing code + its public key to CHAR_PAIRING
 * 3. Provider validates code, writes its own public key to CHAR_PUBKEY
 * 4. Provider writes WiFi connection info to CHAR_WIFI_INFO
 * 5. Both sides derive session key from ECDH + pairing code
 */
class BlePairingServer(
    private val context: Context,
    private val sessionCrypto: SessionCrypto,
    private val expectedPairingCode: String,
    private val deviceId: String,
    private val wifiHost: String,
    private val wifiPort: Int,
    private val onPairingComplete: (PairingResult) -> Unit,
    private val onPairingFailed: (String) -> Unit,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val server = bluetoothManager.openGattServer(context, gattCallback) ?: run {
            onPairingFailed("Cannot open GATT server")
            return
        }
        gattServer = server

        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            // Identity: readable, contains deviceId
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BleConstants.CHAR_IDENTITY_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
            )
            // Pairing: writable, consumer sends code + pubkey
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BleConstants.CHAR_PAIRING_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            )
            // PubKey: readable, provider's ECDH public key
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BleConstants.CHAR_PUBKEY_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
            )
            // WiFi info: readable after pairing
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BleConstants.CHAR_WIFI_INFO_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
            )
        }

        server.addService(service)
        Log.i(TAG, "GATT pairing server started")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gattServer?.close()
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val response = when (characteristic.uuid) {
                BleConstants.CHAR_IDENTITY_UUID -> deviceId.toByteArray(Charsets.UTF_8)
                BleConstants.CHAR_PUBKEY_UUID -> sessionCrypto.publicKeyBase64.toByteArray(Charsets.UTF_8)
                BleConstants.CHAR_WIFI_INFO_UUID -> "$wifiHost:$wifiPort".toByteArray(Charsets.UTF_8)
                else -> null
            }

            if (response != null) {
                val chunk = if (offset < response.size) {
                    response.copyOfRange(offset, response.size)
                } else {
                    byteArrayOf()
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid == BleConstants.CHAR_PAIRING_UUID && value != null) {
                handlePairingWrite(device, requestId, value, responseNeeded)
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        private fun handlePairingWrite(
            device: BluetoothDevice,
            requestId: Int,
            value: ByteArray,
            responseNeeded: Boolean,
        ) {
            // Expected format: "CODE|BASE64_PUBKEY|DEVICE_ID"
            val payload = String(value, Charsets.UTF_8)
            val parts = payload.split("|", limit = 3)

            if (parts.size < 3) {
                Log.w(TAG, "Malformed pairing payload")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                onPairingFailed("Malformed pairing data")
                return
            }

            val (code, remotePubKey, remoteDeviceId) = parts

            if (code != expectedPairingCode) {
                Log.w(TAG, "Wrong pairing code")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                onPairingFailed("Wrong pairing code")
                return
            }

            // Derive session key
            try {
                sessionCrypto.deriveSessionKey(remotePubKey, code)
            } catch (e: Exception) {
                Log.e(TAG, "Key derivation failed", e)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                onPairingFailed("Key derivation failed: ${e.message}")
                return
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            Log.i(TAG, "Pairing successful with $remoteDeviceId")
            onPairingComplete(
                PairingResult(
                    remoteDeviceId = remoteDeviceId,
                    remotePublicKey = remotePubKey,
                    wifiHost = wifiHost,
                    wifiPort = wifiPort,
                )
            )
        }
    }
}
