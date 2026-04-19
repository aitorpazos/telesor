package dev.remoty.ble

import java.util.UUID

/** BLE constants for Remoty discovery and pairing. */
object BleConstants {
    /** Custom GATT service UUID for Remoty. */
    val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")

    /** Characteristic: device advertises its role + name. */
    val CHAR_IDENTITY_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")

    /** Characteristic: write pairing code + public key here. */
    val CHAR_PAIRING_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")

    /** Characteristic: read the remote's public key after pairing. */
    val CHAR_PUBKEY_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567893")

    /** Characteristic: read WiFi connection info (IP:port) after pairing. */
    val CHAR_WIFI_INFO_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567894")

    /** Manufacturer ID in BLE advertisement (arbitrary, in "user" range). */
    const val MANUFACTURER_ID = 0xFFAA

    /** Magic bytes to identify Remoty advertisements. */
    val ADVERTISEMENT_MAGIC = byteArrayOf(0x52, 0x4D, 0x54, 0x59) // "RMTY"
}
