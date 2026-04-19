package dev.remoty.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    @Test
    fun `Hello packet serialization roundtrip`() {
        val hello = RemotyPacket.Hello(
            deviceId = "test-device-123",
            deviceName = "Pixel 9",
            capabilities = DeviceCapabilities(
                hasCamera = true,
                hasFrontCamera = true,
                hasBackCamera = true,
                hasNfc = true,
            ),
            protocolVersion = 1,
        )

        val jsonStr = json.encodeToString<RemotyPacket>(hello)
        assertTrue(jsonStr.contains("test-device-123"))
        assertTrue(jsonStr.contains("Pixel 9"))

        val decoded = json.decodeFromString<RemotyPacket>(jsonStr)
        assertTrue(decoded is RemotyPacket.Hello)
        val decodedHello = decoded as RemotyPacket.Hello
        assertEquals("test-device-123", decodedHello.deviceId)
        assertEquals("Pixel 9", decodedHello.deviceName)
        assertTrue(decodedHello.capabilities.hasCamera)
        assertTrue(decodedHello.capabilities.hasNfc)
    }

    @Test
    fun `StartCamera packet serialization roundtrip`() {
        val start = RemotyPacket.StartCamera(
            width = 1920,
            height = 1080,
            fps = 60,
            facing = RemotyPacket.CameraFacing.FRONT,
            bitrate = 4_000_000,
        )

        val jsonStr = json.encodeToString<RemotyPacket>(start)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr) as RemotyPacket.StartCamera
        assertEquals(1920, decoded.width)
        assertEquals(1080, decoded.height)
        assertEquals(60, decoded.fps)
        assertEquals(RemotyPacket.CameraFacing.FRONT, decoded.facing)
        assertEquals(4_000_000, decoded.bitrate)
    }

    @Test
    fun `StartCamera default values`() {
        val start = RemotyPacket.StartCamera()
        assertEquals(1280, start.width)
        assertEquals(720, start.height)
        assertEquals(30, start.fps)
        assertEquals(RemotyPacket.CameraFacing.BACK, start.facing)
        assertEquals(2_000_000, start.bitrate)
    }

    @Test
    fun `CameraStarted packet serialization`() {
        val started = RemotyPacket.CameraStarted(
            width = 1280,
            height = 720,
            fps = 30,
            codecMime = "video/avc",
            bitrate = 2_000_000,
        )

        val jsonStr = json.encodeToString<RemotyPacket>(started)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr) as RemotyPacket.CameraStarted
        assertEquals("video/avc", decoded.codecMime)
    }

    @Test
    fun `NfcApduCommand packet serialization`() {
        val cmd = RemotyPacket.NfcApduCommand(
            apdu = "00A40400070000000000000000",
            requestId = 42,
        )

        val jsonStr = json.encodeToString<RemotyPacket>(cmd)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr) as RemotyPacket.NfcApduCommand
        assertEquals("00A40400070000000000000000", decoded.apdu)
        assertEquals(42L, decoded.requestId)
    }

    @Test
    fun `NfcApduResponse packet serialization`() {
        val resp = RemotyPacket.NfcApduResponse(
            response = "9000",
            requestId = 42,
        )

        val jsonStr = json.encodeToString<RemotyPacket>(resp)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr) as RemotyPacket.NfcApduResponse
        assertEquals("9000", decoded.response)
        assertEquals(42L, decoded.requestId)
    }

    @Test
    fun `NfcTagDetected packet serialization`() {
        val tag = RemotyPacket.NfcTagDetected(
            uid = "04AABBCCDD",
            techList = listOf("android.nfc.tech.IsoDep", "android.nfc.tech.NfcA"),
            atsHex = "0506070809",
        )

        val jsonStr = json.encodeToString<RemotyPacket>(tag)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr) as RemotyPacket.NfcTagDetected
        assertEquals("04AABBCCDD", decoded.uid)
        assertEquals(2, decoded.techList.size)
        assertEquals("0506070809", decoded.atsHex)
    }

    @Test
    fun `Ping Pong packet serialization`() {
        val ping = RemotyPacket.Ping(timestamp = 1234567890L)
        val jsonStr = json.encodeToString<RemotyPacket>(ping)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr) as RemotyPacket.Ping
        assertEquals(1234567890L, decoded.timestamp)

        val pong = RemotyPacket.Pong(echoTimestamp = 1234567890L)
        val jsonStr2 = json.encodeToString<RemotyPacket>(pong)
        val decoded2 = json.decodeFromString<RemotyPacket>(jsonStr2) as RemotyPacket.Pong
        assertEquals(1234567890L, decoded2.echoTimestamp)
    }

    @Test
    fun `Bye packet serialization`() {
        val bye = RemotyPacket.Bye
        val jsonStr = json.encodeToString<RemotyPacket>(bye)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr)
        assertTrue(decoded is RemotyPacket.Bye)
    }

    @Test
    fun `StopCamera and CameraStopped serialization`() {
        val stop = RemotyPacket.StopCamera
        val jsonStr = json.encodeToString<RemotyPacket>(stop)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr)
        assertTrue(decoded is RemotyPacket.StopCamera)

        val stopped = RemotyPacket.CameraStopped
        val jsonStr2 = json.encodeToString<RemotyPacket>(stopped)
        val decoded2 = json.decodeFromString<RemotyPacket>(jsonStr2)
        assertTrue(decoded2 is RemotyPacket.CameraStopped)
    }

    @Test
    fun `NFC relay control packets serialization`() {
        listOf(
            RemotyPacket.NfcStartRelay,
            RemotyPacket.NfcRelayStarted,
            RemotyPacket.NfcStopRelay,
            RemotyPacket.NfcRelayStopped,
            RemotyPacket.NfcTagLost,
            RemotyPacket.RequestKeyFrame,
        ).forEach { packet ->
            val jsonStr = json.encodeToString<RemotyPacket>(packet)
            val decoded = json.decodeFromString<RemotyPacket>(jsonStr)
            assertEquals(packet::class, decoded::class)
        }
    }

    @Test
    fun `Error packet serialization`() {
        val error = RemotyPacket.Error(code = 500, message = "Internal error")
        val jsonStr = json.encodeToString<RemotyPacket>(error)
        val decoded = json.decodeFromString<RemotyPacket>(jsonStr) as RemotyPacket.Error
        assertEquals(500, decoded.code)
        assertEquals("Internal error", decoded.message)
    }

    @Test
    fun `DeviceCapabilities defaults`() {
        val caps = DeviceCapabilities()
        assertFalse(caps.hasCamera)
        assertFalse(caps.hasFrontCamera)
        assertFalse(caps.hasBackCamera)
        assertFalse(caps.hasNfc)
    }

    @Test
    fun `PairedDevice serialization`() {
        val device = PairedDevice(
            id = "uuid-123",
            name = "Test Device",
            publicKey = "base64key==",
            lastSeenTimestamp = 1700000000L,
        )

        val jsonStr = json.encodeToString(device)
        val decoded = json.decodeFromString<PairedDevice>(jsonStr)
        assertEquals("uuid-123", decoded.id)
        assertEquals("Test Device", decoded.name)
        assertEquals("base64key==", decoded.publicKey)
        assertEquals(1700000000L, decoded.lastSeenTimestamp)
    }

    @Test
    fun `PairedDevice default lastSeenTimestamp`() {
        val device = PairedDevice(id = "x", name = "y", publicKey = "z")
        assertEquals(0L, device.lastSeenTimestamp)
    }
}
