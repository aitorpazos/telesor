package dev.telesor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.util.Log
import dev.telesor.crypto.SessionCrypto

private const val TAG = "BlePairingClient"

/**
 * Maximum ATT MTU we request. Android caps at 517.
 * The usable payload per GATT write/read = MTU − 3.
 */
private const val REQUESTED_MTU = 512

/**
 * GATT client used by the **consumer** to complete the pairing handshake
 * with a provider's [BlePairingServer].
 *
 * Flow:
 * 1. Connect to the provider's GATT server
 * 2. Request a large MTU (the pairing payload is ~164 bytes)
 * 3. Discover services
 * 4. Read provider's identity (device ID)
 * 5. Read provider's ECDH public key
 * 6. Write our pairing code + public key + device ID to the pairing characteristic
 * 7. Read WiFi connection info (IP:port) from provider
 * 8. Derive shared session key from ECDH + pairing code
 * 9. Report success with [PairingResult]
 */
class BlePairingClient(
    private val context: android.content.Context,
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
        private var negotiatedMtu: Int = 23 // BLE default

        // ---- Buffers for long reads (offset-based reassembly) ----
        private var readBuffer = StringBuilder()
        private var currentReadUuid: java.util.UUID? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected, requesting MTU $REQUESTED_MTU")
                g.requestMtu(REQUESTED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected (status=$status)")
                if (remotePubKey == null) {
                    onPairingFailed("Connection lost during pairing")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i(TAG, "MTU negotiated: $negotiatedMtu (status=$status)")
            g.discoverServices()
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
            startLongRead(g, BleConstants.CHAR_IDENTITY_UUID)
        }

        // ---------- Long read helpers ----------

        /**
         * Begin reading a characteristic. Clears the buffer and issues the
         * first readCharacteristic call; subsequent offset reads happen in
         * [handleCharRead] when the returned chunk fills the (MTU−1) window.
         */
        private fun startLongRead(g: BluetoothGatt, uuid: java.util.UUID) {
            readBuffer.clear()
            currentReadUuid = uuid
            val ch = service!!.getCharacteristic(uuid)
            g.readCharacteristic(ch)
        }

        // API < 33 callback
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

        // API 33+ callback
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

            val chunk = String(value, Charsets.UTF_8)
            readBuffer.append(chunk)

            // BLE long-read: if the chunk exactly fills the ATT payload
            // (MTU − 1), there may be more data. Android's stack issues
            // automatic "Read Blob" requests for us, so each callback
            // delivers the next offset chunk. A short chunk means we're done.
            val attPayload = negotiatedMtu - 1
            if (value.size >= attPayload) {
                // More data expected — Android will trigger the next read
                // automatically (Read Blob Request). Nothing to do here.
                return
            }

            // Full value assembled
            val fullValue = readBuffer.toString()
            readBuffer.clear()

            when (uuid) {
                BleConstants.CHAR_IDENTITY_UUID -> {
                    remoteDeviceId = fullValue
                    Log.i(TAG, "Got remote device ID: $fullValue, reading pubkey")
                    startLongRead(g, BleConstants.CHAR_PUBKEY_UUID)
                }

                BleConstants.CHAR_PUBKEY_UUID -> {
                    remotePubKey = fullValue
                    Log.i(TAG, "Got remote pubkey (${fullValue.length} chars), writing pairing data")
                    writePairingData(g)
                }

                BleConstants.CHAR_WIFI_INFO_UUID -> {
                    wifiInfo = fullValue
                    Log.i(TAG, "Got WiFi info: $fullValue")
                    completePairing()
                }
            }
        }

        // ---------- Write ----------

        @SuppressLint("MissingPermission")
        private fun writePairingData(g: BluetoothGatt) {
            val payload = "$pairingCode|${sessionCrypto.publicKeyBase64}|$deviceId"
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            Log.i(TAG, "Pairing payload size: ${payloadBytes.size} bytes, MTU: $negotiatedMtu")

            val pairingChar = service!!.getCharacteristic(BleConstants.CHAR_PAIRING_UUID)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    pairingChar,
                    payloadBytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
            } else {
                @Suppress("DEPRECATION")
                pairingChar.value = payloadBytes
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
                startLongRead(g, BleConstants.CHAR_WIFI_INFO_UUID)
            }
        }

        // ---------- Complete ----------

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
