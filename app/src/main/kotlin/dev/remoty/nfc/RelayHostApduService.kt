package dev.remoty.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

private const val TAG = "RelayHostApdu"

/**
 * NFC Host Card Emulation service that relays APDU commands
 * to the remote provider device over the encrypted channel.
 *
 * When an NFC reader (on the consumer device) selects our AID,
 * we forward the APDU to the provider's physical NFC reader,
 * which taps it against a real tag, and returns the response.
 */
class RelayHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val hexCmd = commandApdu.toHex()
        Log.d(TAG, "APDU command received: $hexCmd")

        // TODO: forward to RemotyChannel and wait for response
        // For now, return a "command not allowed" status word
        return byteArrayOf(0x6A.toByte(), 0x82.toByte())
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: reason=$reason")
        // TODO: notify channel that tag session ended
    }

    companion object {
        /** Reference to the active channel for APDU relay. Set by the connection manager. */
        @Volatile
        var activeRelay: ApduRelay? = null
    }
}

/** Interface for relaying APDU commands over the network. */
interface ApduRelay {
    suspend fun relayApdu(command: ByteArray): ByteArray
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
