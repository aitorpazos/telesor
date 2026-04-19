package dev.telesor.nfc

import org.junit.Assert.*
import org.junit.Test

class NfcHelpersTest {

    @Test
    fun `toHex converts bytes correctly`() {
        assertEquals("", byteArrayOf().toHex())
        assertEquals("00", byteArrayOf(0x00).toHex())
        assertEquals("FF", byteArrayOf(0xFF.toByte()).toHex())
        assertEquals("0102030405", byteArrayOf(1, 2, 3, 4, 5).toHex())
        assertEquals("DEADBEEF", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toHex())
    }

    @Test
    fun `hexToByteArray converts correctly`() {
        assertArrayEquals(byteArrayOf(), "".hexToByteArray())
        assertArrayEquals(byteArrayOf(0x00), "00".hexToByteArray())
        assertArrayEquals(byteArrayOf(0xFF.toByte()), "FF".hexToByteArray())
        assertArrayEquals(byteArrayOf(0xFF.toByte()), "ff".hexToByteArray())
        assertArrayEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            "DEADBEEF".hexToByteArray()
        )
    }

    @Test
    fun `toHex and hexToByteArray are inverse operations`() {
        val original = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val hex = original.toHex()
        val roundtripped = hex.hexToByteArray()
        assertArrayEquals(original, roundtripped)
    }

    @Test(expected = IllegalStateException::class)
    fun `hexToByteArray rejects odd-length strings`() {
        "ABC".hexToByteArray()
    }

    @Test
    fun `SW error codes are correct`() {
        // Conditions not satisfied
        val sw6985 = byteArrayOf(0x69.toByte(), 0x85.toByte())
        assertEquals("6985", sw6985.toHex())

        // No precise diagnosis
        val sw6F00 = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        assertEquals("6F00", sw6F00.toHex())

        // Success
        val sw9000 = byteArrayOf(0x90.toByte(), 0x00.toByte())
        assertEquals("9000", sw9000.toHex())
    }
}
