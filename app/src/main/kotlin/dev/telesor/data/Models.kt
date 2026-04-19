package dev.telesor.data

import kotlinx.serialization.Serializable

/** Role this device plays in the current session. */
enum class DeviceRole {
    /** Has camera + NFC, streams to consumer. */
    PROVIDER,
    /** Receives streams, creates virtual camera / NFC relay. */
    CONSUMER
}

/** Persisted info about a paired device. */
@Serializable
data class PairedDevice(
    val id: String,            // UUID generated at first pairing
    val name: String,          // user-visible device name
    val publicKey: String,     // base64-encoded ECDH public key
    val lastSeenTimestamp: Long = 0L,
)

/** Transient connection state. */
enum class ConnectionState {
    DISCONNECTED,
    BLE_DISCOVERING,
    BLE_CONNECTED,
    PAIRING,
    UPGRADING_TO_WIFI,
    CONNECTED,
    STREAMING,
    ERROR,
}

/** Capabilities a provider can advertise. */
@Serializable
data class DeviceCapabilities(
    val hasCamera: Boolean = false,
    val hasFrontCamera: Boolean = false,
    val hasBackCamera: Boolean = false,
    val hasNfc: Boolean = false,
)
