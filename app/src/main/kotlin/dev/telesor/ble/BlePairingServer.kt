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
 *
 * NOTE: The pairing payload (~140 bytes) exceeds the default BLE ATT MTU (20 bytes).
 * Even after MTU negotiation, Android may use "prepared writes" (preparedWrite=true)
 * which deliver data in chunks. This server buffers those chunks and processes
 * the full payload in onExecuteWrite().
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
            // Support both regular write and long/prepared writes
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

        /**
         * Buffer for prepared (long) writes. BLE long writes arrive as multiple
         * onCharacteristicWriteRequest calls with preparedWrite=true, each carrying
         * a chunk at a given offset. We assemble the full payload here and process
         * it when onExecuteWrite is called.
         */
        private val preparedWriteBuffer = mutableMapOf<String, ByteArray>()

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "Server MTU changed to $mtu for ${device.address}")
        }

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
            if (characteristic.uuid != BleConstants.CHAR_PAIRING_UUID || value == null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            if (preparedWrite) {
                // Long write — buffer the chunk at the given offset.
                // We'll process the full payload in onExecuteWrite().
                val key = device.address
                val existing = preparedWriteBuffer[key] ?: byteArrayOf()

                // Extend the buffer to fit offset + value
                val needed = offset + value.size
                val buffer = if (existing.size < needed) {
                    existing.copyOf(needed)
                } else {
                    existing
                }
                System.arraycopy(value, 0, buffer, offset, value.size)
                preparedWriteBuffer[key] = buffer

                Log.d(TAG, "Prepared write chunk: offset=$offset, size=${value.size}, total=${buffer.size}")

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                // Regular (non-prepared) write — payload fits in a single ATT write.
                Log.i(TAG, "Direct write: ${value.size} bytes")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                handlePairingPayload(device, value)
            }
        }

        override fun onExecuteWrite(
            device: BluetoothDevice,
            requestId: Int,
            execute: Boolean,
        ) {
            val key = device.address
            val buffer = preparedWriteBuffer.remove(key)

            if (execute && buffer != null) {
                Log.i(TAG, "Execute write: ${buffer.size} bytes assembled")
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                handlePairingPayload(device, buffer)
            } else {
                // Cancelled or no data
                Log.w(TAG, "Execute write cancelled or empty")
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        private fun handlePairingPayload(
            device: BluetoothDevice,
            value: ByteArray,
        ) {
            // Expected format: "CODE|BASE64_PUBKEY|DEVICE_ID"
            val payload = String(value, Charsets.UTF_8)
            val parts = payload.split("|", limit = 3)

            Log.i(TAG, "Pairing payload: ${payload.length} chars, parts=${parts.size}")

            if (parts.size < 3) {
                Log.w(TAG, "Malformed pairing payload: got ${parts.size} parts from ${payload.length} chars")
                onPairingFailed("Malformed pairing data")
                return
            }

            val (code, remotePubKey, remoteDeviceId) = parts

            if (code != expectedPairingCode) {
                Log.w(TAG, "Wrong pairing code")
                onPairingFailed("Wrong pairing code")
                return
            }

            // Derive session key
            try {
                sessionCrypto.deriveSessionKey(remotePubKey, code)
            } catch (e: Exception) {
                Log.e(TAG, "Key derivation failed", e)
                onPairingFailed("Key derivation failed: ${e.message}")
                return
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
