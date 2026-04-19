package dev.telesor.data

import kotlinx.serialization.Serializable

/**
 * Wire protocol messages exchanged over the encrypted TCP channel.
 * Each message is length-prefixed (4-byte big-endian) + JSON payload,
 * except for FRAME_DATA which is length-prefixed + raw bytes.
 */
@Serializable
sealed interface TelesorPacket {

    /** Sent right after TCP connection to declare identity & caps. */
    @Serializable
    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val capabilities: DeviceCapabilities,
        val protocolVersion: Int = 1,
    ) : TelesorPacket

    /** Consumer requests a camera stream. */
    @Serializable
    data class StartCamera(
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val facing: CameraFacing = CameraFacing.BACK,
        val bitrate: Int = 2_000_000,
    ) : TelesorPacket

    @Serializable
    enum class CameraFacing { FRONT, BACK }

    /** Provider acknowledges camera start with actual negotiated params. */
    @Serializable
    data class CameraStarted(
        val width: Int,
        val height: Int,
        val fps: Int,
        val codecMime: String = "video/avc", // H.264
        val bitrate: Int = 2_000_000,
    ) : TelesorPacket

    /** Stop camera stream. */
    @Serializable
    data object StopCamera : TelesorPacket

    /** Provider notifies that camera stream has stopped. */
    @Serializable
    data object CameraStopped : TelesorPacket

    /** Consumer requests re-send of SPS/PPS config data. */
    @Serializable
    data object RequestKeyFrame : TelesorPacket

    /** Consumer requests provider to start NFC reader mode. */
    @Serializable
    data object NfcStartRelay : TelesorPacket

    /** Provider acknowledges NFC reader mode is active. */
    @Serializable
    data object NfcRelayStarted : TelesorPacket

    /** Stop NFC relay. */
    @Serializable
    data object NfcStopRelay : TelesorPacket

    /** Provider confirms NFC relay stopped. */
    @Serializable
    data object NfcRelayStopped : TelesorPacket

    /** NFC: consumer forwards an APDU command to provider's NFC reader. */
    @Serializable
    data class NfcApduCommand(
        val apdu: String, // hex-encoded
        val requestId: Long = 0, // correlation ID for matching responses
    ) : TelesorPacket

    /** NFC: provider returns APDU response. */
    @Serializable
    data class NfcApduResponse(
        val response: String, // hex-encoded
        val requestId: Long = 0,
    ) : TelesorPacket

    /** NFC: provider detected a tag. */
    @Serializable
    data class NfcTagDetected(
        val uid: String, // hex-encoded
        val techList: List<String>,
        val atsHex: String? = null, // Answer To Select for ISO-DEP
    ) : TelesorPacket

    /** NFC: tag removed. */
    @Serializable
    data object NfcTagLost : TelesorPacket

    /** Ping/pong for keepalive. */
    @Serializable
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : TelesorPacket

    @Serializable
    data class Pong(val echoTimestamp: Long) : TelesorPacket

    /** Graceful disconnect. */
    @Serializable
    data object Bye : TelesorPacket

    /** Error notification. */
    @Serializable
    data class Error(val code: Int, val message: String) : TelesorPacket
}
