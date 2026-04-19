package dev.telesor.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "RelayHostApdu"

/**
 * NFC Host Card Emulation service that relays APDU commands
 * from an external NFC reader (touching the consumer device)
 * to the remote provider device over the encrypted channel.
 *
 * Flow:
 *   External NFC reader → touches consumer device
 *   → Android routes APDU to this service (via AID matching)
 *   → We send NfcApduCommand over TelesorChannel to provider
 *   → Provider calls IsoDep.transceive() on the physical tag
 *   → Provider sends NfcApduResponse back
 *   → We return the response to the external NFC reader
 *
 * This enables use cases like:
 *   - FIDO2/passkey scanning: external reader reads "virtual tag" on consumer,
 *     which transparently proxies to a real FIDO2 key on the provider
 *   - Any ISO-DEP based protocol relay
 */
class RelayHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        val relay = activeRelay
        if (relay == null) {
            Log.w(TAG, "No active relay — returning error SW")
            return SW_CONDITIONS_NOT_SATISFIED
        }

        val hexCmd = commandApdu.toHex()
        val requestId = nextRequestId.getAndIncrement()
        Log.d(TAG, "APDU [$requestId]: $hexCmd")

        // processCommandApdu runs on the binder thread.
        // We need to block until we get the response from the remote provider.
        // Return null to use sendResponseApdu() asynchronously would be cleaner,
        // but many NFC stacks expect a synchronous response.
        // We use runBlocking with a timeout to avoid hanging forever.
        return try {
            runBlocking {
                val response = withTimeoutOrNull(RELAY_TIMEOUT_MS) {
                    relay.relayApdu(commandApdu, requestId)
                }
                if (response != null) {
                    Log.d(TAG, "APDU [$requestId] response: ${response.toHex()}")
                    response
                } else {
                    Log.w(TAG, "APDU [$requestId] timeout after ${RELAY_TIMEOUT_MS}ms")
                    SW_TIMEOUT
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "APDU relay error", e)
            SW_INTERNAL_ERROR
        }
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "LINK_LOSS"
            DEACTIVATION_DESELECTED -> "DESELECTED"
            else -> "UNKNOWN($reason)"
        }
        Log.d(TAG, "Deactivated: $reasonStr")
        activeRelay?.onDeactivated()
    }

    companion object {
        private const val RELAY_TIMEOUT_MS = 5000L
        private val nextRequestId = AtomicLong(1)

        /** Reference to the active channel relay. Set by NfcSessionManager. */
        @Volatile
        var activeRelay: ApduRelay? = null

        // Standard SW (Status Word) error codes
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())
        private val SW_TIMEOUT = byteArrayOf(0x6F.toByte(), 0x01.toByte())
        private val SW_INTERNAL_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }
}

/**
 * Interface for relaying APDU commands over the network channel.
 *
 * Implemented by [NfcSessionManager] which sends commands to the
 * provider and waits for responses.
 */
interface ApduRelay {
    /**
     * Send an APDU command to the remote provider and wait for the response.
     *
     * @param command Raw APDU command bytes
     * @param requestId Correlation ID for matching request/response
     * @return Raw APDU response bytes from the remote tag
     */
    suspend fun relayApdu(command: ByteArray, requestId: Long): ByteArray

    /** Called when the HCE session is deactivated (link loss or deselect). */
    fun onDeactivated()
}

/**
 * Thread-safe store for pending APDU responses.
 *
 * The HCE service sends a command and blocks waiting for a response.
 * The channel packet handler delivers the response here, unblocking the waiter.
 */
class PendingApduResponses {
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<ByteArray>>()

    /** Create a deferred for the given request ID. */
    fun expect(requestId: Long): CompletableDeferred<ByteArray> {
        val deferred = CompletableDeferred<ByteArray>()
        pending[requestId] = deferred
        return deferred
    }

    /** Deliver a response for the given request ID. */
    fun deliver(requestId: Long, response: ByteArray): Boolean {
        val deferred = pending.remove(requestId)
        return if (deferred != null) {
            deferred.complete(response)
            true
        } else {
            Log.w(TAG, "No pending request for ID $requestId")
            false
        }
    }

    /** Cancel all pending requests (e.g., on disconnect). */
    fun cancelAll() {
        pending.forEach { (_, deferred) ->
            deferred.cancel()
        }
        pending.clear()
    }
}
