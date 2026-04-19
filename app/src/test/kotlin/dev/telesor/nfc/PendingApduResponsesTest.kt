package dev.telesor.nfc

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PendingApduResponsesTest {

    @Test
    fun `expect and deliver completes deferred`() = runBlocking {
        val pending = PendingApduResponses()
        val deferred = pending.expect(1L)
        val response = byteArrayOf(0x90.toByte(), 0x00.toByte())

        val delivered = pending.deliver(1L, response)
        assertTrue(delivered)
        assertArrayEquals(response, deferred.await())
    }

    @Test
    fun `deliver with unknown requestId returns false`() {
        val pending = PendingApduResponses()
        val delivered = pending.deliver(999L, byteArrayOf())
        assertFalse(delivered)
    }

    @Test
    fun `cancelAll cancels all pending deferreds`() = runBlocking {
        val pending = PendingApduResponses()
        val d1 = pending.expect(1L)
        val d2 = pending.expect(2L)
        val d3 = pending.expect(3L)

        pending.cancelAll()

        assertTrue(d1.isCancelled)
        assertTrue(d2.isCancelled)
        assertTrue(d3.isCancelled)
    }

    @Test
    fun `multiple concurrent expect-deliver pairs`() = runBlocking {
        val pending = PendingApduResponses()

        val results = (1L..10L).map { id ->
            val deferred = pending.expect(id)
            async {
                deferred.await()
            }
        }

        // Deliver in reverse order
        (10L downTo 1L).forEach { id ->
            val response = byteArrayOf(id.toByte(), 0x00)
            pending.deliver(id, response)
        }

        results.forEachIndexed { index, deferred ->
            val result = deferred.await()
            assertEquals((index + 1).toByte(), result[0])
        }
    }

    @Test
    fun `deliver after cancelAll returns false`() {
        val pending = PendingApduResponses()
        pending.expect(1L)
        pending.cancelAll()
        val delivered = pending.deliver(1L, byteArrayOf())
        assertFalse(delivered)
    }

    @Test
    fun `expect same id twice overwrites`() = runBlocking {
        val pending = PendingApduResponses()
        val d1 = pending.expect(1L)
        val d2 = pending.expect(1L) // overwrites d1

        val response = byteArrayOf(0x42)
        pending.deliver(1L, response)

        // d2 should complete, d1 should NOT complete (it was replaced)
        assertArrayEquals(response, d2.await())
        // d1 is orphaned — it won't complete or cancel, just stays pending
        assertFalse(d1.isCompleted)
    }
}
