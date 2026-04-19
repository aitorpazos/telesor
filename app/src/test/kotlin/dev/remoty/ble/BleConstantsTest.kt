package dev.remoty.ble

import dev.remoty.data.DeviceRole
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BleConstantsTest {

    @Test
    fun `SERVICE_UUID is valid UUID`() {
        val uuid = BleConstants.SERVICE_UUID
        assertNotNull(uuid)
        assertEquals(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"), uuid)
    }

    @Test
    fun `all characteristic UUIDs are unique`() {
        val uuids = setOf(
            BleConstants.CHAR_IDENTITY_UUID,
            BleConstants.CHAR_PAIRING_UUID,
            BleConstants.CHAR_PUBKEY_UUID,
            BleConstants.CHAR_WIFI_INFO_UUID,
        )
        assertEquals("All UUIDs must be unique", 4, uuids.size)
    }

    @Test
    fun `ADVERTISEMENT_MAGIC is RMTY`() {
        val magic = BleConstants.ADVERTISEMENT_MAGIC
        assertEquals(4, magic.size)
        assertEquals("RMTY", String(magic, Charsets.US_ASCII))
    }

    @Test
    fun `MANUFACTURER_ID is in user range`() {
        assertTrue(BleConstants.MANUFACTURER_ID > 0)
        assertEquals(0xFFAA, BleConstants.MANUFACTURER_ID)
    }
}
