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
 * Long-read support: read requests with offset > 0 return subsequent chunks
 * of the full value, allowing the BLE stack to reassemble values larger than
 * (MTU − 1) bytes via Read Blob requests.
 *
 * Long-write support: if the consumer's write payload exceeds (MTU − 3),
 * Android may deliver it as a Prepared Write sequence. We accumulate chunks
 * in [preparedWrites] and process the full payload in [onExecuteWrite].
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

    // Pre-computed full byte arrays for readable characteristics
    private val identityBytes by lazy { deviceId.toByteArray(Charsets.UTF_8) }
    private val pubKeyBytes by lazy { sessionCrypto.publicKeyBase64.toByteArray(Charsets.UTF_8) }
    private val wifiInfoBytes by lazy { "$wifiHost:$wifiPort".toByteArray(Charsets.UTF_8) }

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
        Log.i(TAG, "GATT pairing server started (identity=${identityBytes.size}B, " +
                "pubkey=${pubKeyBytes.size}B, wifi=${wifiInfoBytes.size}B)")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gattServer?.close()
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattServerCallback() {

        /** Buffer for prepared (long) writes per device. */
        private val preparedWrites = mutableMapOf<String, ByteArray>()

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU changed to $mtu for ${device.address}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val fullValue = when (characteristic.uuid) {
                BleConstants.CHAR_IDENTITY_UUID -> identityBytes
                BleConstants.CHAR_PUBKEY_UUID -> pubKeyBytes
                BleConstants.CHAR_WIFI_INFO_UUID -> wifiInfoBytes
                else -> null
            }

            if (fullValue != null) {
                // Return the chunk starting at `offset`.
                // The BLE stack sends Read Blob requests with increasing
                // offsets until we return a chunk shorter than (MTU − 1).
                val chunk = if (offset < fullValue.size) {
                    fullValue.copyOfRange(offset, fullValue.size)
                } else {
                    byteArrayOf() // offset past end → empty → signals "done"
                }
                Log.d(TAG, "Read ${characteristic.uuid} offset=$offset chunk=${chunk.size}B total=${fullValue.size}B")
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
            if (characteristic.uuid != BleConstants.CHAR_PAIRING_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            val data = value ?: byteArrayOf()

            if (preparedWrite) {
                // Long write: accumulate chunks. We'll process in onExecuteWrite.
                val key = device.address
                val existing = preparedWrites[key] ?: byteArrayOf()
                // Ensure the buffer is large enough for the offset
                val newBuf = if (offset + data.size > existing.size) {
                    existing.copyOf(offset + data.size)
                } else {
                    existing.copyOf()
                }
                System.arraycopy(data, 0, newBuf, offset, data.size)
                preparedWrites[key] = newBuf
                Log.d(TAG, "Prepared write chunk: offset=$offset size=${data.size}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
                }
            } else {
                // Regular (short) write — process immediately
                if (responseNeeded) {
                    // Send response before processing so the client isn't blocked
                    val result = processPairingPayload(data)
                    val status = if (result) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    gattServer?.sendResponse(device, requestId, status, 0, null)
                } else {
                    processPairingPayload(data)
                }
            }
        }

        override fun onExecuteWrite(
            device: BluetoothDevice,
            requestId: Int,
            execute: Boolean,
        ) {
            val key = device.address
            val payload = preparedWrites.remove(key)

            if (!execute || payload == null) {
                // Client cancelled the prepared write
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }

            Log.i(TAG, "Execute write: ${payload.size} bytes from ${device.address}")
            val result = processPairingPayload(payload)
            val status = if (result) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            gattServer?.sendResponse(device, requestId, status, 0, null)
        }

        /**
         * Parse and validate the pairing payload.
         * Expected format: "CODE|BASE64_PUBKEY|DEVICE_ID"
         * @return true if pairing succeeded
         */
        private fun processPairingPayload(value: ByteArray): Boolean {
            val payload = String(value, Charsets.UTF_8)
            val parts = payload.split("|", limit = 3)

            Log.i(TAG, "Pairing payload: ${payload.length} chars, ${parts.size} parts")

            if (parts.size < 3) {
                Log.w(TAG, "Malformed pairing payload (${parts.size} parts, expected 3). " +
                        "Payload length=${payload.length}, first 40 chars: ${payload.take(40)}")
                onPairingFailed("Malformed pairing data")
                return false
            }

            val (code, remotePubKey, remoteDeviceId) = parts

            if (code != expectedPairingCode) {
                Log.w(TAG, "Wrong pairing code")
                onPairingFailed("Wrong pairing code")
                return false
            }

            // Derive session key
            try {
                sessionCrypto.deriveSessionKey(remotePubKey, code)
            } catch (e: Exception) {
                Log.e(TAG, "Key derivation failed", e)
                onPairingFailed("Key derivation failed: ${e.message}")
                return false
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
            return true
        }
    }
}
