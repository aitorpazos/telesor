package dev.telesor.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "NfcReaderManager"

/**
 * Provider-side NFC tag reader.
 *
 * Uses [NfcAdapter.enableReaderMode] to gain exclusive access to the NFC
 * controller and detect ISO-DEP tags. When a tag is detected, it opens
 * an [IsoDep] connection and waits for APDU commands from the consumer
 * (relayed over the encrypted channel).
 *
 * Architecture:
 *   Consumer HCE ← NFC reader (external) ← APDU → relay → Provider NfcReaderManager
 *                                                            ↕ IsoDep.transceive()
 *                                                          Physical NFC tag
 */
class NfcReaderManager {

    /** Current NFC relay state. */
    enum class NfcState {
        IDLE,
        WAITING_FOR_TAG,
        TAG_CONNECTED,
        ERROR,
    }

    private val _state = MutableStateFlow(NfcState.IDLE)
    val state: StateFlow<NfcState> = _state

    private var nfcAdapter: NfcAdapter? = null
    private var currentTag: Tag? = null
    private var currentIsoDep: IsoDep? = null

    /** Emits tag detection events. */
    val tagEvents = Channel<TagEvent>(Channel.BUFFERED)

    sealed interface TagEvent {
        data class Detected(
            val uid: ByteArray,
            val techList: List<String>,
            val ats: ByteArray?,
        ) : TagEvent

        data object Lost : TagEvent
    }

    /**
     * Start NFC reader mode on the given activity.
     *
     * Enables reader mode with ISO-DEP support. The NFC controller is
     * claimed exclusively — no other NFC dispatch happens while active.
     */
    fun start(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) {
            Log.e(TAG, "NFC not available on this device")
            _state.value = NfcState.ERROR
            return
        }
        nfcAdapter = adapter

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK // We want raw ISO-DEP, not NDEF

        val extras = Bundle().apply {
            // Debounce: presence check delay in ms (lower = faster tag-lost detection)
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }

        adapter.enableReaderMode(activity, readerCallback, flags, extras)
        _state.value = NfcState.WAITING_FOR_TAG
        Log.i(TAG, "NFC reader mode enabled")
    }

    /**
     * Stop NFC reader mode. Closes any open ISO-DEP connection.
     */
    fun stop(activity: Activity) {
        try {
            nfcAdapter?.disableReaderMode(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Error disabling reader mode", e)
        }
        closeIsoDep()
        currentTag = null
        nfcAdapter = null
        _state.value = NfcState.IDLE
        Log.i(TAG, "NFC reader mode disabled")
    }

    /**
     * Transceive an APDU command to the currently connected tag.
     *
     * @param commandApdu Raw APDU command bytes
     * @return Raw APDU response bytes, or a SW error if no tag is connected
     */
    fun transceive(commandApdu: ByteArray): ByteArray {
        val isoDep = currentIsoDep
        if (isoDep == null || !isoDep.isConnected) {
            Log.w(TAG, "No ISO-DEP tag connected for transceive")
            // Return "Conditions not satisfied" (6985)
            return byteArrayOf(0x69.toByte(), 0x85.toByte())
        }

        return try {
            val response = isoDep.transceive(commandApdu)
            Log.d(TAG, "APDU: ${commandApdu.toHex()} → ${response.toHex()}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Transceive failed", e)
            // Tag lost during transceive — notify
            handleTagLost()
            // Return "No precise diagnosis" (6F00)
            byteArrayOf(0x6F.toByte(), 0x00.toByte())
        }
    }

    /** Whether a tag is currently connected via ISO-DEP. */
    val isTagConnected: Boolean
        get() = currentIsoDep?.isConnected == true

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        Log.i(TAG, "Tag discovered: uid=${tag.id.toHex()}, techs=${tag.techList.toList()}")

        currentTag = tag

        // Try to open ISO-DEP (required for APDU relay)
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            try {
                isoDep.connect()
                isoDep.timeout = 5000 // 5 second timeout for transceive
                currentIsoDep = isoDep
                _state.value = NfcState.TAG_CONNECTED

                val ats = try { isoDep.historicalBytes ?: isoDep.hiLayerResponse } catch (_: Exception) { null }

                tagEvents.trySend(
                    TagEvent.Detected(
                        uid = tag.id,
                        techList = tag.techList.toList(),
                        ats = ats,
                    )
                )

                Log.i(TAG, "ISO-DEP connected, timeout=${isoDep.timeout}ms, maxTransceive=${isoDep.maxTransceiveLength}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect ISO-DEP", e)
                _state.value = NfcState.WAITING_FOR_TAG
            }
        } else {
            Log.w(TAG, "Tag does not support ISO-DEP — APDU relay not possible")
            // Still notify about the tag, but without ISO-DEP we can't relay APDUs
            tagEvents.trySend(
                TagEvent.Detected(
                    uid = tag.id,
                    techList = tag.techList.toList(),
                    ats = null,
                )
            )
            _state.value = NfcState.WAITING_FOR_TAG
        }
    }

    private fun handleTagLost() {
        closeIsoDep()
        currentTag = null
        _state.value = NfcState.WAITING_FOR_TAG
        tagEvents.trySend(TagEvent.Lost)
        Log.i(TAG, "Tag lost")
    }

    private fun closeIsoDep() {
        try {
            currentIsoDep?.close()
        } catch (_: Exception) {}
        currentIsoDep = null
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

internal fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
