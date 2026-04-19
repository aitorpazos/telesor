package dev.remoty.data

import kotlinx.serialization.Serializable

/**
 * Wire protocol messages exchanged over the encrypted TCP channel.
 * Each message is length-prefixed (4-byte big-endian) + JSON payload,
 * except for FRAME_DATA which is length-prefixed + raw bytes.
 */
@Serializable
sealed interface RemotyPacket {

    /** Sent right after TCP connection to declare identity & caps. */
    @Serializable
    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val capabilities: DeviceCapabilities,
        val protocolVersion: Int = 1,
    ) : RemotyPacket

    /** Consumer requests a camera stream. */
    @Serializable
    data class StartCamera(
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val facing: CameraFacing = CameraFacing.BACK,
        val bitrate: Int = 2_000_000,
    ) : RemotyPacket

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
    ) : RemotyPacket

    /** Stop camera stream. */
    @Serializable
    data object StopCamera : RemotyPacket

    /** Provider notifies that camera stream has stopped. */
    @Serializable
    data object CameraStopped : RemotyPacket

    /** Consumer requests re-send of SPS/PPS config data. */
    @Serializable
    data object RequestKeyFrame : RemotyPacket

    /** NFC: consumer forwards an APDU command to provider's NFC reader. */
    @Serializable
    data class NfcApduCommand(
        val apdu: String, // hex-encoded
    ) : RemotyPacket

    /** NFC: provider returns APDU response. */
    @Serializable
    data class NfcApduResponse(
        val response: String, // hex-encoded
    ) : RemotyPacket

    /** NFC: provider detected a tag. */
    @Serializable
    data class NfcTagDetected(
        val uid: String, // hex-encoded
        val techList: List<String>,
    ) : RemotyPacket

    /** NFC: tag removed. */
    @Serializable
    data object NfcTagLost : RemotyPacket

    /** Ping/pong for keepalive. */
    @Serializable
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : RemotyPacket

    @Serializable
    data class Pong(val echoTimestamp: Long) : RemotyPacket

    /** Graceful disconnect. */
    @Serializable
    data object Bye : RemotyPacket

    /** Error notification. */
    @Serializable
    data class Error(val code: Int, val message: String) : RemotyPacket
}
