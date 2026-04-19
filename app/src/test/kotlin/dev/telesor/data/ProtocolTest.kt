package dev.telesor.data

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
        val hello = TelesorPacket.Hello(
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

        val jsonStr = json.encodeToString<TelesorPacket>(hello)
        assertTrue(jsonStr.contains("test-device-123"))
        assertTrue(jsonStr.contains("Pixel 9"))

        val decoded = json.decodeFromString<TelesorPacket>(jsonStr)
        assertTrue(decoded is TelesorPacket.Hello)
        val decodedHello = decoded as TelesorPacket.Hello
        assertEquals("test-device-123", decodedHello.deviceId)
        assertEquals("Pixel 9", decodedHello.deviceName)
        assertTrue(decodedHello.capabilities.hasCamera)
        assertTrue(decodedHello.capabilities.hasNfc)
    }

    @Test
    fun `StartCamera packet serialization roundtrip`() {
        val start = TelesorPacket.StartCamera(
            width = 1920,
            height = 1080,
            fps = 60,
            facing = TelesorPacket.CameraFacing.FRONT,
            bitrate = 4_000_000,
        )

        val jsonStr = json.encodeToString<TelesorPacket>(start)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr) as TelesorPacket.StartCamera
        assertEquals(1920, decoded.width)
        assertEquals(1080, decoded.height)
        assertEquals(60, decoded.fps)
        assertEquals(TelesorPacket.CameraFacing.FRONT, decoded.facing)
        assertEquals(4_000_000, decoded.bitrate)
    }

    @Test
    fun `StartCamera default values`() {
        val start = TelesorPacket.StartCamera()
        assertEquals(1280, start.width)
        assertEquals(720, start.height)
        assertEquals(30, start.fps)
        assertEquals(TelesorPacket.CameraFacing.BACK, start.facing)
        assertEquals(2_000_000, start.bitrate)
    }

    @Test
    fun `CameraStarted packet serialization`() {
        val started = TelesorPacket.CameraStarted(
            width = 1280,
            height = 720,
            fps = 30,
            codecMime = "video/avc",
            bitrate = 2_000_000,
        )

        val jsonStr = json.encodeToString<TelesorPacket>(started)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr) as TelesorPacket.CameraStarted
        assertEquals("video/avc", decoded.codecMime)
    }

    @Test
    fun `NfcApduCommand packet serialization`() {
        val cmd = TelesorPacket.NfcApduCommand(
            apdu = "00A40400070000000000000000",
            requestId = 42,
        )

        val jsonStr = json.encodeToString<TelesorPacket>(cmd)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr) as TelesorPacket.NfcApduCommand
        assertEquals("00A40400070000000000000000", decoded.apdu)
        assertEquals(42L, decoded.requestId)
    }

    @Test
    fun `NfcApduResponse packet serialization`() {
        val resp = TelesorPacket.NfcApduResponse(
            response = "9000",
            requestId = 42,
        )

        val jsonStr = json.encodeToString<TelesorPacket>(resp)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr) as TelesorPacket.NfcApduResponse
        assertEquals("9000", decoded.response)
        assertEquals(42L, decoded.requestId)
    }

    @Test
    fun `NfcTagDetected packet serialization`() {
        val tag = TelesorPacket.NfcTagDetected(
            uid = "04AABBCCDD",
            techList = listOf("android.nfc.tech.IsoDep", "android.nfc.tech.NfcA"),
            atsHex = "0506070809",
        )

        val jsonStr = json.encodeToString<TelesorPacket>(tag)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr) as TelesorPacket.NfcTagDetected
        assertEquals("04AABBCCDD", decoded.uid)
        assertEquals(2, decoded.techList.size)
        assertEquals("0506070809", decoded.atsHex)
    }

    @Test
    fun `Ping Pong packet serialization`() {
        val ping = TelesorPacket.Ping(timestamp = 1234567890L)
        val jsonStr = json.encodeToString<TelesorPacket>(ping)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr) as TelesorPacket.Ping
        assertEquals(1234567890L, decoded.timestamp)

        val pong = TelesorPacket.Pong(echoTimestamp = 1234567890L)
        val jsonStr2 = json.encodeToString<TelesorPacket>(pong)
        val decoded2 = json.decodeFromString<TelesorPacket>(jsonStr2) as TelesorPacket.Pong
        assertEquals(1234567890L, decoded2.echoTimestamp)
    }

    @Test
    fun `Bye packet serialization`() {
        val bye = TelesorPacket.Bye
        val jsonStr = json.encodeToString<TelesorPacket>(bye)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr)
        assertTrue(decoded is TelesorPacket.Bye)
    }

    @Test
    fun `StopCamera and CameraStopped serialization`() {
        val stop = TelesorPacket.StopCamera
        val jsonStr = json.encodeToString<TelesorPacket>(stop)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr)
        assertTrue(decoded is TelesorPacket.StopCamera)

        val stopped = TelesorPacket.CameraStopped
        val jsonStr2 = json.encodeToString<TelesorPacket>(stopped)
        val decoded2 = json.decodeFromString<TelesorPacket>(jsonStr2)
        assertTrue(decoded2 is TelesorPacket.CameraStopped)
    }

    @Test
    fun `NFC relay control packets serialization`() {
        listOf(
            TelesorPacket.NfcStartRelay,
            TelesorPacket.NfcRelayStarted,
            TelesorPacket.NfcStopRelay,
            TelesorPacket.NfcRelayStopped,
            TelesorPacket.NfcTagLost,
            TelesorPacket.RequestKeyFrame,
        ).forEach { packet ->
            val jsonStr = json.encodeToString<TelesorPacket>(packet)
            val decoded = json.decodeFromString<TelesorPacket>(jsonStr)
            assertEquals(packet::class, decoded::class)
        }
    }

    @Test
    fun `Error packet serialization`() {
        val error = TelesorPacket.Error(code = 500, message = "Internal error")
        val jsonStr = json.encodeToString<TelesorPacket>(error)
        val decoded = json.decodeFromString<TelesorPacket>(jsonStr) as TelesorPacket.Error
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
