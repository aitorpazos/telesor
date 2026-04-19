package dev.remoty.data

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {

    @Test
    fun `DeviceRole enum values`() {
        assertEquals(2, DeviceRole.entries.size)
        assertNotNull(DeviceRole.valueOf("PROVIDER"))
        assertNotNull(DeviceRole.valueOf("CONSUMER"))
    }

    @Test
    fun `ConnectionState enum values`() {
        val states = ConnectionState.entries
        assertTrue(states.size >= 7)
        assertNotNull(ConnectionState.valueOf("DISCONNECTED"))
        assertNotNull(ConnectionState.valueOf("BLE_DISCOVERING"))
        assertNotNull(ConnectionState.valueOf("BLE_CONNECTED"))
        assertNotNull(ConnectionState.valueOf("PAIRING"))
        assertNotNull(ConnectionState.valueOf("UPGRADING_TO_WIFI"))
        assertNotNull(ConnectionState.valueOf("CONNECTED"))
        assertNotNull(ConnectionState.valueOf("STREAMING"))
        assertNotNull(ConnectionState.valueOf("ERROR"))
    }

    @Test
    fun `DeviceCapabilities data class`() {
        val caps = DeviceCapabilities(
            hasCamera = true,
            hasFrontCamera = true,
            hasBackCamera = false,
            hasNfc = true,
        )
        assertTrue(caps.hasCamera)
        assertTrue(caps.hasFrontCamera)
        assertFalse(caps.hasBackCamera)
        assertTrue(caps.hasNfc)

        // copy
        val caps2 = caps.copy(hasBackCamera = true)
        assertTrue(caps2.hasBackCamera)
    }

    @Test
    fun `PairedDevice data class equality`() {
        val d1 = PairedDevice(id = "a", name = "A", publicKey = "pk1")
        val d2 = PairedDevice(id = "a", name = "A", publicKey = "pk1")
        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())

        val d3 = PairedDevice(id = "b", name = "A", publicKey = "pk1")
        assertNotEquals(d1, d3)
    }
}
